#ifndef ADAPTIVE_TRANSPORT_DIAGNOSTICS_H
#define ADAPTIVE_TRANSPORT_DIAGNOSTICS_H

enum adaptive_transport_phase {
    ADAPTIVE_HEV_CONFIG = 1,
    ADAPTIVE_HEV_LOGGER = 2,
    ADAPTIVE_HEV_TASKS = 3,
    ADAPTIVE_HEV_TUN_INIT = 4,
    ADAPTIVE_HEV_LOOP = 5,
    ADAPTIVE_TUN_READ = 6,
    ADAPTIVE_TUN_WRITE = 7,
    ADAPTIVE_TUN_BUFFER = 8,
};

void adaptive_transport_fault(int phase, int result, int native_errno);

#endif
