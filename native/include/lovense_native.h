#ifndef LOVENSE_NATIVE_H
#define LOVENSE_NATIVE_H

#ifdef __cplusplus
extern "C" {
#endif

/* Starts the device-control engine. Returns an empty string on success,
 * or a user-facing error message. The caller owns the returned string
 * and must release it with lovense_native_free_string. */
char *lovense_native_initialize(void);

/* Submits one JSON command (the same wire format used on Android).
 * Returns an empty string on success, or an error message. */
char *lovense_native_submit(const char *command);

/* Drains and returns queued engine events as a JSON array. Never fails;
 * returns "[]" when there is nothing new or the engine hasn't started. */
char *lovense_native_drain(void);

/* Releases a string previously returned by any function above. */
void lovense_native_free_string(char *value);

#ifdef __cplusplus
}
#endif

#endif
