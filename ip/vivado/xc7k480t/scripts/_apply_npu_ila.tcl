################################################################################
# _apply_npu_ila.tcl — Insert an ILA core into the NPU design via mark_debug.
#
# Called AFTER synth_design completes (synth_1 opened) but BEFORE impl_1.
# Scans the marked-debug nets in npu_dma_master, builds an ILA core, and
# connects each mark_debug net group (one per base signal name) to a probe.
#
# After impl + bitstream, Vivado emits an .ltx file alongside the .bit; load
# both in Vivado HW Manager → ILA dashboard to capture waveforms.
#
# Typical trigger for a read-path / write-path debug session:
#     state == 4'd6  (S_READ_ACC_R)  — watch ACCUM-read activity
#     state == 4'd10 (S_WR_W)        — watch result-writeback activity
#
# Probes (one per base signal, derived from (* mark_debug = "true" *) tags
# in `ip/vivado/xc7k480t/src/npu_dma_master.v`):
#     state            (4 bits) — FSM state
#     beat_cnt         (4 bits) — current write index into a/b/acc_buf
#     rpipe_valid      (1 bit)  — internal "beat available" pulse
#     rlast_pipe       (1 bit)  — last-beat marker (pipelined)
#     rdata_pipe[31:0] (32 bits) — low 32 bits of the registered AXI read
#     dbg_rvalid       (1 bit)  — sampled m_axi_rvalid (shadow)
#     dbg_rready       (1 bit)  — sampled m_axi_rready (shadow)
#     dbg_rlast        (1 bit)  — sampled m_axi_rlast (shadow)
#     dbg_rdata_lo     (32 bits) — sampled m_axi_rdata[31:0] (shadow)
#     dbg_arvalid      (1 bit)  — sampled m_axi_arvalid (shadow)
#     dbg_arready      (1 bit)  — sampled m_axi_arready (shadow)
#     dbg_state_d1     (4 bits) — state delayed by 1 cycle
#     dbg_beat_cnt_d1  (4 bits) — beat_cnt delayed by 1 cycle
#
# Required: synth checkpoint open (open_run synth_1 -name synth_1) AND the
# design RTL contains (* mark_debug = "true" *) on the signals above.
################################################################################

proc _npu_ila_strip_index {n} {
    # "u_dma/state_reg[2]_Q" or ".../state[2]" → ".../state"
    set name [get_property NAME $n]
    regsub {\[\d+\]$} $name {} name
    regsub {_reg(\[\d+\])?$} $name {} name
    return $name
}

proc insert_npu_ila {} {
    puts "=== _apply_npu_ila: inserting ILA into synth_1 checkpoint ==="

    if {[catch {current_design} _]} {
        puts "ERROR: no current design — open_run synth_1 first"
        return 0
    }

    set mdnets [get_nets -hierarchical -filter {MARK_DEBUG == 1}]
    set n [llength $mdnets]
    puts "INFO: found $n mark_debug net(s)"
    if {$n == 0} {
        puts "WARN: no mark_debug nets — skipping ILA insertion"
        return 1
    }

    # Build {base_name -> list_of_nets} table
    array set groups {}
    foreach netobj $mdnets {
        set base [_npu_ila_strip_index $netobj]
        lappend groups($base) $netobj
    }
    set probe_count [array size groups]
    puts "INFO: grouped into $probe_count probe(s)"

    # Find fabric_aclk net (must match the dma_master clock domain)
    set clk_net ""
    foreach cand [get_nets -hierarchical -filter \
                      {NAME =~ *clk_wiz_fabric*clk_out1*}] {
        set clk_net $cand; break
    }
    if {$clk_net eq ""} {
        foreach cand [get_nets -hierarchical -filter {NAME =~ *fabric_aclk*}] {
            set clk_net $cand; break
        }
    }
    if {$clk_net eq ""} {
        puts "ERROR: cannot find fabric clock net for ILA"
        return 0
    }
    puts "INFO: ILA clock = $clk_net"

    # Create the debug core (one ILA for all probes).
    create_debug_core u_npu_ila ila
    set_property C_DATA_DEPTH         4096  [get_debug_cores u_npu_ila]
    set_property C_TRIGIN_EN          false [get_debug_cores u_npu_ila]
    set_property C_TRIGOUT_EN         false [get_debug_cores u_npu_ila]
    set_property C_ADV_TRIGGER        false [get_debug_cores u_npu_ila]
    set_property C_INPUT_PIPE_STAGES  0     [get_debug_cores u_npu_ila]
    set_property C_EN_STRG_QUAL       false [get_debug_cores u_npu_ila]
    set_property ALL_PROBE_SAME_MU    true  [get_debug_cores u_npu_ila]
    set_property ALL_PROBE_SAME_MU_CNT 1    [get_debug_cores u_npu_ila]

    connect_debug_port u_npu_ila/clk [get_nets $clk_net]

    # The IP creates probe0 by default. We need (probe_count - 1) more.
    set probe_idx 0
    foreach base [array names groups] {
        set nets $groups($base)
        set width [llength $nets]
        if {$probe_idx > 0} {
            create_debug_port u_npu_ila probe
        }
        set port "u_npu_ila/probe$probe_idx"
        set_property PORT_WIDTH $width [get_debug_ports $port]
        connect_debug_port $port [get_nets $nets]
        puts "INFO: probe$probe_idx ($width bits) ← $base"
        incr probe_idx
    }

    puts "INFO: ILA insertion complete: $probe_idx probe(s) on u_npu_ila"
    return 1
}
