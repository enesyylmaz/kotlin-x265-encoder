# Android X265 Encoder

A lightweight Android video encoder leveraging FFmpeg with libx265 codec for personal use. Built for ARM64 (ARMv8) architecture and Android 14, with potential compatibility for lower Android versions (untested).

Currently tested only on Samsung Galaxy S23. Note: Device may experience significant heating during encoding.

The encoder implements the following FFmpeg configuration:
```kotlin
val cmd = arrayOf(
    "-i", job.inputPath,
    "-c:v", "libx265",
    "-preset", "faster",
    "-crf", "23",
    "-x265-params", "fast_decode=1",
    "-threads", "${Runtime.getRuntime().availableProcessors() / 2}",
    job.outputPath
)
```

Note: Output files are saved in the same directory as the input file, with '_x265' appended to the original filename.

To be made:
- Device compatibility testing
- Background processing optimization
- Heat management solutions
- Support for additional ARM architectures (ARMv7, ARMv6)
- Support for older Android versions
- Support for various video formats and codecs
