#include <stdio.h>
#include <stdlib.h>
#ifdef __cplusplus
extern "C" {
#endif
extern void amxx_assert_report(const char *msg) __attribute__((weak));
static void assert_report(const char *msg) {
  fprintf(stderr, "%s", msg);
  if (amxx_assert_report)
    amxx_assert_report(msg);
}
__attribute__((noreturn)) void __wrap___assert2(const char *file, int line,
                                                const char *func,
                                                const char *failedexpr) {
  {
    char msg[512];
    snprintf(msg, sizeof(msg), "[ASSERT] %s:%d: %s: %s\n", file, line, func,
             failedexpr);
    assert_report(msg);
  }
  abort();
}
__attribute__((noreturn)) void __wrap___assert_fail(const char *assertion,
                                                    const char *file,
                                                    unsigned int line,
                                                    const char *function) {
  {
    char msg[512];
    snprintf(msg, sizeof(msg), "[ASSERT] %s:%d (%s): %s\n", file, line, function,
             assertion);
    assert_report(msg);
  }
  abort();
}
#ifdef __cplusplus
}
#endif