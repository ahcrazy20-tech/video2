These one-second synthetic clips (solid color and a 440 Hz sine wave) are fixtures for verifying real AVC and AAC track headers. They contain no third-party content and no network URLs.

```sh
ffmpeg -f lavfi -i 'color=c=teal:s=64x48:r=10:d=1' -an -c:v libx264 -pix_fmt yuv420p -movflags +frag_keyframe+empty_moov fixture-picture.mp4
ffmpeg -f lavfi -i 'sine=frequency=440:sample_rate=16000:duration=1' -vn -c:a aac -b:a 20k -movflags +frag_keyframe+empty_moov fixture-sound.m4a
```
