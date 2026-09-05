# Real-Time Audio Thread Execution Contract

## 1. Absolute Scope & Purpose

This contract defines the strict operational rules governing the real-time audio render callback and data path in **Antigravity Player** (`writeDirect`, `AudiophileDsp::process`, `AudiophileResampler::process`, and `OboeAudioSink.handleBuffer`).

Violations of this contract introduce audio dropouts, priority inversion, jitter, underruns (xruns), or thread deadlocks. Every rule in this specification is physically enforced in code.

---

## 2. Forbidden Operations (Hard Real-Time Violations)

The audio render path (`writeDirect` / `process`) **MUST NEVER** execute any of the following operations under any circumstance:

| Category | Forbidden Operation | Why Prohibited | Antigravity Player Enforcement |
| :--- | :--- | :--- | :--- |
| **Heap Memory** | `malloc()`, `calloc()`, `realloc()`, `free()` | Non-deterministic allocator lock contention and page faults | Preallocated scratch and ring buffers during `openStream()` |
| **C++ Dynamic Memory** | `new`, `delete`, `new[]`, `delete[]` | Invokes global memory allocator; unbounded latency | Zero dynamic object construction in audio loops |
| **Container Mutation** | `std::vector::push_back()`, `resize()`, `assign()` | Can trigger heap reallocation and copy overhead | Fixed preallocated capacities (`std::array`, fixed vectors) |
| **String Allocation** | `std::string` construction / concatenation | Allocates heap memory for strings exceeding SSO | Pure numerical arrays; zero string construction in hot path |
| **Refcount Lifecycle** | Constructing/resetting `std::shared_ptr` | May trigger atomic refcount destruction and `delete` | Raw pointers accessed via hazard-pointer `SlotLease` |
| **Mutual Exclusion** | `std::mutex::lock()`, `try_lock()`, `pthread_mutex` | Priority inversion, thread unscheduling by kernel | Lock-free atomics and triple buffer exchange only |
| **Thread Blocking** | `sleep()`, `nanosleep()`, `usleep()`, `sched_yield()` | Deschedules real-time audio thread from CPU core | Strict zero-blocking execution |
| **I/O & System Calls** | Filesystem read/write, database access, sockets | Unbounded kernel wait times | Decoupled completely to worker/IO threads |
| **Diagnostics / Logging** | `__android_log_print()`, `printf()`, `Log.i()` | Takes system IPC binder locks to logd; high latency | Stripped in release; zero logging in active write loop |
| **JNI Allocations** | `env->NewFloatArray()`, `NewByteArray()`, `FindClass()` | JVM garbage collection pressure, JNI table locks | Zero-allocation `writeDirect` using direct ByteBuffers |
| **Android Preferences** | `SharedPreferences` read/write | Disk synchronization, XML parsing locks | Pre-cached in memory by `EqualizerEngine` |

---

## 3. Allowed Operations on the Render Thread

The following operations are permitted and verified safe on the real-time audio thread:

1. **Direct Pointer Arithmetic**:
   - Reading/writing to preallocated contiguous memory buffers (`pcmFloatScratchBuffer`, `outputBuffer_`).
2. **Lock-Free Atomic Loads & Exchanges**:
   - `std::memory_order_relaxed` / `acquire` / `release` operations.
   - `cleanSlot_.exchange(readSlot_, std::memory_order_acq_rel)` for triple buffer snapshot swap.
   - Reader lease increment/decrement (`activeUsers.fetch_add/sub`).
3. **Fixed-Size Numerical Math**:
   - Double-precision biquad Direct Form II Transposed filtering.
   - 5th-order Padé rational approximation for soft-knee true-peak limiting.
   - 64-phase windowed-sinc polyphase FIR convolution.
   - TPDF dither generation using 64-bit XorShift PRNG (zero allocations).
4. **Direct Oboe Stream I/O**:
   - `stream->write(scratch, numFrames, timeoutNs)` with bounded 20ms timeout.
   - Direct JNI memory mapping via `env->GetDirectBufferAddress()`.

---

## 4. Ownership & Synchronization Rules

### Rule 4.1 — Unidirectional Parameter Flow
- Control parameters are constructed **exclusively on the control thread** under `paramWriteMutex_`.
- Filter coefficients are precalculated on the control thread before publication.
- Render thread only reads published snapshots; it never computes filter design formulas (e.g. `sin`, `cos`, `tan` for biquad pole/zero derivation).

### Rule 4.2 — Triple-Buffer Protocol
- Snapshot pool consists of exactly 3 slots: `snapshotPool_[3]`.
- Control thread owns `writeSlot_`.
- Render thread owns `readSlot_`.
- Thread synchronization occurs solely through atomic swaps on `cleanSlot_`.
- At all times, `{readSlot_, writeSlot_, cleanSlot_}` is a permutation of `{0, 1, 2}`. Writer and reader can never access the same slot simultaneously.

### Rule 4.3 — Hazard-Pointer Stream Leasing
- Render thread claims a reader lease on the active stream slot via `slot.activeUsers.fetch_add(1)`.
- If the stream is being closed, `unregisterStream()` waits for in-flight writers to drop `activeUsers` to 0 before destroying stream resources.

---

## 5. Summary Verification Checklist

| Criterion | Native Oboe Path | Kotlin Fallback Path | Verification Status |
| :--- | :--- | :--- | :--- |
| **Zero Heap Allocations** | Verified (`pcmFloatScratchBuffer`) | Verified (Preallocated frame buffers) | **ENFORCED** |
| **Zero Mutex Locks in Hot Path**| Verified (`SlotLease` lock-free) | Verified (Atomic snapshot swap) | **ENFORCED** |
| **Zero Logging in Write Loop** | Verified (No log calls during write) | Verified (No logging during queueInput) | **ENFORCED** |
| **Bounded Execution Time** | $< 1.2\text{ ms}$ for 512 frames | $< 2.5\text{ ms}$ for 512 frames | **ENFORCED** |
| **Numeric Safety** | Clamp $[-1.0, 1.0]$, NaN/Inf guards | NaN/Inf checks, bounded gains | **ENFORCED** |
