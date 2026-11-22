################################################################################
# impl_pre.tcl — project-run pre-hook for place/phys_opt/route
#
# Vivado project runs are generated with `set_param general.maxThreads 2` in
# init_design, so a set_param in the parent launch_runs process does not reach
# the implementation steps.  build_npu_vu9p.tcl installs this file as the
# STEPS.*.TCL.PRE hook; it runs in the child process immediately before each
# step and restores the requested thread count and ML model mode.
#
# Environment:
#   NPU_MAX_THREADS   1..8 (default 8)
#   NPU_ML            on | off | hybrid (default on)
################################################################################

set _threads 8
if {[info exists ::env(NPU_MAX_THREADS)] && $::env(NPU_MAX_THREADS) ne ""} {
    set _threads $::env(NPU_MAX_THREADS)
} elseif {[info exists ::env(VIVADO_MAX_THREADS)] && $::env(VIVADO_MAX_THREADS) ne ""} {
    set _threads $::env(VIVADO_MAX_THREADS)
}
set_param general.maxThreads $_threads

set _ml on
if {[info exists ::env(NPU_ML)] && $::env(NPU_ML) ne ""} {
    set _ml $::env(NPU_ML)
}
switch -- $_ml {
    on {
        catch { set_param place.3d.congestionML 8193 }
        catch { set_param place.disableMlde 0 }
    }
    hybrid {
        catch { set_param place.3d.congestionML 8193 }
        catch { set_param place.disableMlde 1 }
    }
    off {
        catch { set_param place.3d.congestionML 0 }
        catch { set_param place.disableMlde 1 }
    }
    default {
        puts "WARNING: unknown NPU_ML='$_ml'; using ML off"
        catch { set_param place.3d.congestionML 0 }
        catch { set_param place.disableMlde 1 }
    }
}
puts "IMPL_PRE threads=$_threads ml=$_ml"
