#include <stdlib.h>
#include <time.h>

long teavm_currentTimeMillis() {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return ts.tv_nsec / 1000000 + ts.tv_sec * 1000;
}