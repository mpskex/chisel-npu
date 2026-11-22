#!/usr/bin/env python3
"""Parallel, ML-on Vivado implementation for the xcvu9p NPU.

The build is staged so long routes can be retried from checkpoints:

    place candidates (parallel, ML on)
      -> best placements
      -> phys_opt candidates (parallel)
      -> route candidates (parallel)
      -> best post-route WNS / bitstream

Each candidate runs in its own Vivado process (and, on this host, its own
Ubuntu 22.04 container).  The launcher sizes concurrency from MemAvailable and
physical cores: measured peak is ~13.5 GB and ~2.4 cores per implementation.

Run ``--dry-run`` to print the plan without launching Vivado.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import dataclasses
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Iterable

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_TCL = REPO_ROOT / "ip/vivado/xcvu9p/scripts/impl_candidate.tcl"
DEFAULT_RUNNER = REPO_ROOT / "tool/fpga/run_vivado_container.sh"
DEFAULT_CHECKPOINT = (
    REPO_ROOT
    / "ip/vivado/xcvu9p/prj/prj.runs/impl_1/hw_platform_opt.dcp"
)
DEFAULT_BITSTREAM = REPO_ROOT / "ip/vivado/xcvu9p/top_npu_vu9p.bit"

CRASH_PATTERNS = (
    "Abnormal program termination",
    "SIGSEGV",
    "segfault in",
    "LgbmTree",
    "HCMBoostTreeEstimator",
    "HCMBoost",
)


@dataclasses.dataclass
class RunSpec:
    tag: str
    stage: str
    checkpoint: Path
    out_dir: Path
    place_directive: str = "ExtraTimingOpt"
    route_directive: str = "Default"
    physopt_directive: str = "Default"
    ultrathreads: bool = True
    ml: str = "on"
    threads: int = 8
    bitstream: Path | None = None


@dataclasses.dataclass
class RunResult:
    spec: RunSpec
    ok: bool
    status: str
    wns: float | None
    unrouted: int | None
    seconds: float
    peak_mb: float | None
    log: Path
    result_file: Path
    error: str = ""
    attempts: int = 1

    @property
    def label(self) -> str:
        return f"{self.spec.stage}:{self.spec.tag}"


def _mem_available_gb() -> float:
    try:
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) / (1024 * 1024)
    except OSError:
        pass
    return 0.0


def _physical_cores() -> int:
    try:
        return max(1, len(os.sched_getaffinity(0)) // 2)
    except (AttributeError, OSError):
        return max(1, (os.cpu_count() or 2) // 2)


def _effective_jobs(args: argparse.Namespace) -> int:
    avail = _mem_available_gb()
    by_mem = max(1, int((avail - args.reserve_gb) // args.per_job_gb))
    by_cpu = max(1, int(_physical_cores() // args.cpu_per_job))
    return max(1, min(args.jobs, by_mem, by_cpu))


def _sanitize(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", text).strip("_") or "candidate"


def _read_env_file(path: Path) -> dict[str, str]:
    data: dict[str, str] = {}
    if not path.exists():
        return data
    for line in path.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            data[key.strip()] = value.strip()
    return data


def _extract_float(data: dict[str, str], keys: Iterable[str]) -> float | None:
    for key in keys:
        value = data.get(key, "")
        if value == "":
            continue
        try:
            return float(value)
        except ValueError:
            continue
    return None


def _extract_int(data: dict[str, str], keys: Iterable[str]) -> int | None:
    for key in keys:
        value = data.get(key, "")
        if value == "":
            continue
        try:
            return int(value)
        except ValueError:
            continue
    return None


def _wns_for(stage: str, data: dict[str, str]) -> float | None:
    keys = {
        "place": ("CAND_POSTPLACE_WNS", "CAND_WNS"),
        "physopt": ("CAND_POSTPHYSOPT_WNS", "CAND_WNS"),
        "route": ("CAND_POSTROUTE_WNS", "CAND_WNS"),
        "full": ("CAND_POSTROUTE_WNS", "CAND_WNS"),
        "bitstream": ("CAND_WNS",),
    }.get(stage, ("CAND_WNS",))
    return _extract_float(data, keys)


def _peak_mb(log: Path) -> float | None:
    if not log.exists():
        return None
    best: float | None = None
    pattern = re.compile(r"Memory \(MB\): peak = ([0-9.]+)")
    for match in pattern.finditer(log.read_text(errors="replace")):
        value = float(match.group(1))
        if best is None or value > best:
            best = value
    return best


def _has_crash(log: Path) -> bool:
    if not log.exists():
        return False
    text = log.read_text(errors="replace")
    return any(pattern in text for pattern in CRASH_PATTERNS)


def _output_for_stage(spec: RunSpec) -> Path:
    if spec.stage == "place":
        return spec.out_dir / "placed.dcp"
    if spec.stage == "physopt":
        return spec.out_dir / "physopt.dcp"
    if spec.stage == "route":
        return spec.bitstream or (spec.out_dir / "candidate.bit")
    if spec.stage == "bitstream":
        return spec.bitstream or (spec.out_dir / "candidate.bit")
    return spec.out_dir / "candidate.bit"


def _cached_result(spec: RunSpec) -> RunResult | None:
    result_file = spec.out_dir / "result.env"
    data = _read_env_file(result_file)
    if data.get("CAND_STATUS") != "ok":
        return None
    output = _output_for_stage(spec)
    if not output.exists():
        return None
    log = spec.out_dir / "attempt0.log"
    return RunResult(
        spec=spec,
        ok=True,
        status="cached",
        wns=_wns_for(spec.stage, data),
        unrouted=_extract_int(data, ("CAND_UNROUTED",)),
        seconds=0.0,
        peak_mb=_peak_mb(log),
        log=log,
        result_file=result_file,
    )


def _write_env_file(spec: RunSpec, path: Path, container_name: str) -> None:
    env = {
        "CAND_STAGE": spec.stage,
        "CAND_TAG": spec.tag,
        "CAND_OUT_DIR": str(spec.out_dir),
        "CAND_CHECKPOINT": str(spec.checkpoint),
        "CAND_PLACE_DIRECTIVE": spec.place_directive,
        "CAND_ROUTE_DIRECTIVE": spec.route_directive,
        "CAND_PHYSOPT_DIRECTIVE": spec.physopt_directive,
        "CAND_ULTRATHREADS": "1" if spec.ultrathreads else "0",
        "CAND_ML": spec.ml,
        "CAND_MAX_THREADS": str(spec.threads),
        "CAND_GATE_NS": "-1.0",
        "NPU_CONTAINER_NAME": container_name,
    }
    if spec.bitstream is not None:
        env["CAND_BITSTREAM"] = str(spec.bitstream)
    path.write_text("".join(f"{key}={value}\n" for key, value in env.items()))


def _copy_crash_dumps(spec: RunSpec, attempt: int, started_at: float) -> None:
    for dump in REPO_ROOT.glob("hs_err_pid*.log"):
        try:
            if dump.stat().st_mtime + 5 < started_at:
                continue
        except OSError:
            continue
        target = spec.out_dir / f"hs_err_attempt{attempt}_{dump.name}"
        try:
            shutil.copy2(dump, target)
        except OSError:
            pass


def _run_once(
    spec: RunSpec,
    args: argparse.Namespace,
    attempt: int,
) -> RunResult:
    spec.out_dir.mkdir(parents=True, exist_ok=True)
    tcl = Path(args.tcl).resolve()
    runner = Path(args.runner).resolve()
    if not tcl.exists():
        raise SystemExit(f"candidate Tcl not found: {tcl}")
    if not runner.exists():
        raise SystemExit(f"runner not found: {runner}")
    if not spec.checkpoint.exists():
        raise SystemExit(f"checkpoint not found: {spec.checkpoint}")

    tag = _sanitize(f"{spec.tag}_a{attempt}")
    container_name = f"npu_impl_{tag}"
    env_file = spec.out_dir / f"attempt{attempt}.env"
    log = spec.out_dir / f"attempt{attempt}.log"
    journal = spec.out_dir / f"attempt{attempt}.jou"
    stdout = spec.out_dir / f"attempt{attempt}.stdout"
    _write_env_file(spec, env_file, container_name)
    result_file = spec.out_dir / "result.env"
    result_file.unlink(missing_ok=True)

    env = os.environ.copy()
    env["REPO_ROOT"] = str(REPO_ROOT)
    if args.vivado_root:
        env["VIVADO_ROOT"] = args.vivado_root
    if args.vivado_image:
        env["VIVADO_IMAGE"] = args.vivado_image

    print(f"[{spec.stage}] {spec.tag}: starting attempt {attempt}", flush=True)
    started_at = time.time()
    cmd = ["bash", str(runner), str(env_file), str(tcl), str(log), str(journal)]
    timed_out = False
    try:
        with stdout.open("w") as out_handle:
            subprocess.run(
                cmd,
                cwd=str(REPO_ROOT),
                env=env,
                stdout=out_handle,
                stderr=subprocess.STDOUT,
                timeout=args.timeout,
                check=False,
            )
    except subprocess.TimeoutExpired:
        timed_out = True
        print(f"[{spec.stage}] {spec.tag}: timeout after {args.timeout}s", flush=True)
        subprocess.run(
            ["docker", "rm", "-f", container_name],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    elapsed = time.time() - started_at

    data = _read_env_file(result_file)
    status = data.get("CAND_STATUS", "timeout" if timed_out else "crashed")
    wns = _wns_for(spec.stage, data)
    unrouted = _extract_int(data, ("CAND_UNROUTED",))
    ok = status == "ok" and not timed_out
    if ok and spec.stage in {"route", "full"} and wns is not None:
        ok = wns >= args.gate
    if ok and unrouted not in (None, 0):
        ok = False

    error = ""
    if not ok:
        if timed_out:
            error = "timeout"
        elif _has_crash(log):
            error = "Vivado crash (see hs_err_* copies and attempt log)"
        else:
            error = f"status={status}"
    _copy_crash_dumps(spec, attempt, started_at)
    print(
        f"[{spec.stage}] {spec.tag}: attempt {attempt} "
        f"{'OK' if ok else 'FAIL'} status={status} "
        f"wns={wns if wns is not None else 'n/a'} "
        f"{elapsed:.0f}s",
        flush=True,
    )
    return RunResult(
        spec=spec,
        ok=ok,
        status=status,
        wns=wns,
        unrouted=unrouted,
        seconds=elapsed,
        peak_mb=_peak_mb(log),
        log=log,
        result_file=result_file,
        error=error,
    )


def _retry_spec(spec: RunSpec, attempt: int) -> RunSpec:
    """Return the next attempt's spec: ML-off/thread fallback."""
    if spec.stage in {"route", "full"}:
        threads = 4 if attempt == 1 else 2
        return dataclasses.replace(
            spec,
            ml="off",
            threads=min(threads, spec.threads),
            route_directive="Default" if attempt >= 1 else spec.route_directive,
            ultrathreads=False,
        )
    threads = min(4, spec.threads) if attempt == 1 else 1
    return dataclasses.replace(
        spec, ml="off", threads=threads, ultrathreads=False
    )


def _run_spec(
    spec: RunSpec,
    args: argparse.Namespace,
) -> RunResult:
    if args.resume:
        cached = _cached_result(spec)
        if cached is not None:
            print(f"[{spec.stage}] {spec.tag}: cached", flush=True)
            return cached

    current = spec
    attempts = 0
    result: RunResult | None = None
    for attempt in range(args.retries + 1):
        attempts = attempt + 1
        result = _run_once(current, args, attempt)
        result.attempts = attempts
        if result.ok:
            return result
        if attempt < args.retries:
            current = _retry_spec(current, attempt + 1)
            print(
                f"[{spec.stage}] {spec.tag}: retrying with "
                f"ml={current.ml} threads={current.threads} "
                f"route={current.route_directive}",
                flush=True,
            )
    assert result is not None
    return result


def _run_batch(specs: list[RunSpec], args: argparse.Namespace) -> list[RunResult]:
    if not specs:
        return []
    jobs = min(args.jobs_effective, len(specs))
    if jobs <= 1:
        return [_run_spec(spec, args) for spec in specs]
    results: list[RunResult] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = {pool.submit(_run_spec, spec, args): spec for spec in specs}
        for future in concurrent.futures.as_completed(futures):
            results.append(future.result())
    return results


def _select_best(results: list[RunResult]) -> RunResult | None:
    candidates = [
        result
        for result in results
        if result.ok and result.wns is not None
    ]
    if not candidates:
        return None
    return max(candidates, key=lambda result: (result.wns, -result.seconds))


def _print_summary(results: list[RunResult]) -> None:
    print("\n=== candidate summary ===", flush=True)
    print(
        f"{'stage':9} {'tag':42} {'status':10} {'WNS':>8} {'sec':>7} {'peakMB':>9}",
        flush=True,
    )
    for result in results:
        wns = f"{result.wns:+.3f}" if result.wns is not None else "n/a"
        peak = f"{result.peak_mb:.0f}" if result.peak_mb is not None else "n/a"
        print(
            f"{result.spec.stage:9} {result.spec.tag:42} {result.status:10} "
            f"{wns:>8} {result.seconds:7.0f} {peak:>9}",
            flush=True,
        )
    print(flush=True)


def _write_json(path: Path, results: list[RunResult], selected: RunResult | None) -> None:
    payload = {
        "selected": None
        if selected is None
        else {
            "tag": selected.spec.tag,
            "stage": selected.spec.stage,
            "wns": selected.wns,
            "bitstream": str(_output_for_stage(selected.spec)),
            "place_directive": selected.spec.place_directive,
            "route_directive": selected.spec.route_directive,
            "ultrathreads": selected.spec.ultrathreads,
            "ml": selected.spec.ml,
            "threads": selected.spec.threads,
        },
        "candidates": [
            {
                "tag": result.spec.tag,
                "stage": result.spec.stage,
                "status": result.status,
                "ok": result.ok,
                "wns": result.wns,
                "unrouted": result.unrouted,
                "seconds": result.seconds,
                "peak_mb": result.peak_mb,
                "place_directive": result.spec.place_directive,
                "route_directive": result.spec.route_directive,
                "ultrathreads": result.spec.ultrathreads,
                "ml": result.spec.ml,
                "threads": result.spec.threads,
                "attempts": result.attempts,
                "log": str(result.log),
                "error": result.error,
            }
            for result in results
        ],
    }
    path.write_text(json.dumps(payload, indent=2) + "\n")


def _build_split(args: argparse.Namespace) -> int:
    base = Path(args.out_dir).resolve()
    base.mkdir(parents=True, exist_ok=True)
    checkpoint = Path(args.checkpoint).resolve()
    if not checkpoint.exists():
        raise SystemExit(f"checkpoint not found: {checkpoint}")

    place_directives = [item.strip() for item in args.place_directives.split(",") if item.strip()]
    route_directives = [item.strip() for item in args.route_directives.split(",") if item.strip()]
    if not place_directives:
        place_directives = ["ExtraTimingOpt"]
    if not route_directives:
        route_directives = ["Default"]

    all_results: list[RunResult] = []

    place_specs: list[RunSpec] = []
    for index, directive in enumerate(place_directives):
        tag = f"place{index}_{_sanitize(directive)}"
        place_specs.append(
            RunSpec(
                tag=tag,
                stage="place",
                checkpoint=checkpoint,
                out_dir=base / tag,
                place_directive=directive,
                route_directive=route_directives[0],
                ultrathreads=args.ultrathreads,
                ml=args.ml,
                threads=args.threads,
            )
        )

    print("=== stage 1: placements ===", flush=True)
    place_results = _run_batch(place_specs, args)
    all_results.extend(place_results)
    good_places = [result for result in place_results if result.ok and result.wns is not None]
    good_places.sort(key=lambda result: result.wns, reverse=True)
    good_places = good_places[: args.top_places]
    if not good_places:
        print("ERROR: no placement candidate succeeded", file=sys.stderr)
        _print_summary(all_results)
        _write_json(base / "summary.json", all_results, None)
        return 1
    print(
        "selected placements: "
        + ", ".join(f"{item.spec.tag} ({item.wns:+.3f} ns)" for item in good_places),
        flush=True,
    )

    print("\n=== stage 2: physical optimization ===", flush=True)
    phys_specs: list[RunSpec] = []
    for place_result in good_places:
        phys_specs.append(
            RunSpec(
                tag=f"phys_{place_result.spec.tag}",
                stage="physopt",
                checkpoint=_output_for_stage(place_result.spec),
                out_dir=base / f"phys_{place_result.spec.tag}",
                place_directive=place_result.spec.place_directive,
                route_directive=route_directives[0],
                physopt_directive=args.physopt_directive,
                ultrathreads=False,
                ml="off",
                threads=args.threads,
            )
        )
    phys_results = _run_batch(phys_specs, args)
    all_results.extend(phys_results)
    good_phys = [result for result in phys_results if result.ok]
    if not good_phys:
        print("ERROR: no phys_opt candidate succeeded", file=sys.stderr)
        _print_summary(all_results)
        _write_json(base / "summary.json", all_results, None)
        return 1

    print("\n=== stage 3: routes ===", flush=True)
    route_specs: list[RunSpec] = []
    for phys_result in good_phys:
        for route_directive in route_directives:
            tag = f"route_{phys_result.spec.tag}_{_sanitize(route_directive)}"
            route_specs.append(
                RunSpec(
                    tag=tag,
                    stage="route",
                    checkpoint=_output_for_stage(phys_result.spec),
                    out_dir=base / tag,
                    place_directive=phys_result.spec.place_directive,
                    route_directive=route_directive,
                    physopt_directive=args.physopt_directive,
                    ultrathreads=False,
                    ml=args.ml,
                    threads=args.threads,
                    bitstream=base / tag / "candidate.bit",
                )
            )
    route_results = _run_batch(route_specs, args)
    all_results.extend(route_results)

    selected = _select_best(route_results)
    if selected is None:
        # Last-resort known-good recipe: ML off, single-thread placement,
        # 2-thread route.  It is slower but has produced the published
        # bitstream before; retry from the post-opt checkpoint rather than
        # re-running synthesis.
        print(
            "\n=== fallback: ML off, single-thread place, 2-thread route ===",
            flush=True,
        )
        fallback = RunSpec(
            tag="fallback_mloff_1thread",
            stage="full",
            checkpoint=checkpoint,
            out_dir=base / "fallback_mloff_1thread",
            place_directive="ExtraTimingOpt",
            route_directive="Default",
            physopt_directive=args.physopt_directive,
            ultrathreads=False,
            ml="off",
            threads=1,
            bitstream=base / "fallback_mloff_1thread" / "candidate.bit",
        )
        fallback_result = _run_spec(fallback, args)
        all_results.append(fallback_result)
        if fallback_result.ok:
            selected = fallback_result

    if selected is None:
        print("ERROR: no route candidate succeeded", file=sys.stderr)
        _print_summary(all_results)
        _write_json(base / "summary.json", all_results, None)
        return 1

    destination = Path(args.bitstream_dst).resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(_output_for_stage(selected.spec), destination)
    _print_summary(all_results)
    _write_json(base / "summary.json", all_results, selected)
    print(
        f"SELECTED_CANDIDATE={selected.spec.tag} "
        f"WNS={selected.wns:+.3f} ns\n"
        f"SELECTED_BITSTREAM={destination}\n"
        f"SELECTED_LOG={selected.log}",
        flush=True,
    )
    return 0


def _build_full(args: argparse.Namespace) -> int:
    base = Path(args.out_dir).resolve()
    base.mkdir(parents=True, exist_ok=True)
    checkpoint = Path(args.checkpoint).resolve()
    if not checkpoint.exists():
        raise SystemExit(f"checkpoint not found: {checkpoint}")

    place_directives = [item.strip() for item in args.place_directives.split(",") if item.strip()]
    route_directives = [item.strip() for item in args.route_directives.split(",") if item.strip()]
    specs: list[RunSpec] = []
    for index, place_directive in enumerate(place_directives):
        for route_directive in route_directives:
            tag = f"full{index}_{_sanitize(place_directive)}_{_sanitize(route_directive)}"
            specs.append(
                RunSpec(
                    tag=tag,
                    stage="full",
                    checkpoint=checkpoint,
                    out_dir=base / tag,
                    place_directive=place_directive,
                    route_directive=route_directive,
                    physopt_directive=args.physopt_directive,
                    ultrathreads=args.ultrathreads,
                    ml=args.ml,
                    threads=args.threads,
                    bitstream=base / tag / "candidate.bit",
                )
            )
    print("=== full implementation candidates ===", flush=True)
    results = _run_batch(specs, args)
    selected = _select_best(results)
    if selected is None:
        print("ERROR: no implementation candidate succeeded", file=sys.stderr)
        _print_summary(results)
        _write_json(base / "summary.json", results, None)
        return 1
    destination = Path(args.bitstream_dst).resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(_output_for_stage(selected.spec), destination)
    _print_summary(results)
    _write_json(base / "summary.json", results, selected)
    print(
        f"SELECTED_CANDIDATE={selected.spec.tag} "
        f"WNS={selected.wns:+.3f} ns\n"
        f"SELECTED_BITSTREAM={destination}\n"
        f"SELECTED_LOG={selected.log}",
        flush=True,
    )
    return 0


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("split", "full"), default="split")
    parser.add_argument("--checkpoint", default=str(DEFAULT_CHECKPOINT))
    parser.add_argument("--out-dir", default=str(REPO_ROOT / "build/impl_parallel"))
    parser.add_argument("--bitstream-dst", default=str(DEFAULT_BITSTREAM))
    parser.add_argument("--tcl", default=str(DEFAULT_TCL))
    parser.add_argument("--runner", default=str(DEFAULT_RUNNER))
    parser.add_argument("--vivado-root", default=os.environ.get("VIVADO_ROOT", ""))
    parser.add_argument("--vivado-image", default=os.environ.get("VIVADO_IMAGE", ""))
    parser.add_argument(
        "--place-directives",
        default="ExtraTimingOpt,SSI_SpreadLogic_high,AltSpreadLogic_high",
        help="comma-separated place_design directives",
    )
    parser.add_argument(
        "--route-directives",
        default="Default",
        help="comma-separated route_design directives",
    )
    parser.add_argument("--physopt-directive", default="Default")
    parser.add_argument("--ml", choices=("on", "off", "hybrid"), default="on")
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--jobs", type=int, default=3)
    parser.add_argument("--top-places", type=int, default=3)
    parser.add_argument("--per-job-gb", type=float, default=16.0)
    parser.add_argument("--reserve-gb", type=float, default=6.0)
    parser.add_argument("--cpu-per-job", type=float, default=2.5)
    parser.add_argument("--gate", type=float, default=-1.0)
    parser.add_argument("--timeout", type=int, default=7200)
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    ultra = parser.add_mutually_exclusive_group()
    ultra.add_argument(
        "--ultrathreads",
        dest="ultrathreads",
        action="store_true",
        help="enable place_design -ultrathreads (UltraScale+ SSI)",
    )
    ultra.add_argument(
        "--no-ultrathreads",
        dest="ultrathreads",
        action="store_false",
        help="disable place_design -ultrathreads",
    )
    parser.set_defaults(ultrathreads=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    args.jobs_effective = _effective_jobs(args)
    avail = _mem_available_gb()
    cores = _physical_cores()
    print(
        f"host: MemAvailable={avail:.1f} GiB, physical_cores={cores}, "
        f"jobs requested={args.jobs}, effective={args.jobs_effective} "
        f"(per-job={args.per_job_gb:.0f} GiB, reserve={args.reserve_gb:.0f} GiB)",
        flush=True,
    )
    if args.jobs_effective < args.jobs:
        print(
            "WARNING: concurrency clamped by memory/CPU; "
            "close other Vivado jobs or raise --per-job-gb only with evidence",
            flush=True,
        )

    checkpoint = Path(args.checkpoint).resolve()
    if not checkpoint.exists():
        raise SystemExit(f"checkpoint not found: {checkpoint}")

    if args.dry_run:
        scopes = [item.strip() for item in args.place_directives.split(",") if item.strip()]
        routes = [item.strip() for item in args.route_directives.split(",") if item.strip()]
        print(f"mode={args.mode} checkpoint={checkpoint}")
        print(f"place directives: {', '.join(scopes)}")
        print(f"route directives: {', '.join(routes)}")
        print(f"ml={args.ml} threads={args.threads} ultrathreads={args.ultrathreads}")
        print(
            f"split plan: {len(scopes)} place + {len(scopes)} physopt + "
            f"{len(scopes) * len(routes)} route candidate(s)"
        )
        print(f"bitstream destination: {Path(args.bitstream_dst).resolve()}")
        return 0

    if args.mode == "split":
        return _build_split(args)
    return _build_full(args)


if __name__ == "__main__":
    sys.exit(main())
