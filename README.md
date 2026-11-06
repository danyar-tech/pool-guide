# Pool Guide

An Android overlay that draws aim and power guidelines over a top-down pool game.

**Download:** grab `PoolGuide.apk` from the latest release
(`https://github.com/<owner>/<repo>/releases/latest/download/PoolGuide.apk`), open it on the phone and allow installing from this source.

## How it works

1. **Screen capture** (MediaProjection) grabs a frame. The guide lines hide for ~0.1 s during capture so they are not mistaken for balls.
2. **Table**: the dominant felt colour is found, the largest felt region gives the table, the inner cushion edge is found by walking out from the centre, and the six pockets are refined to the darkest blobs near where pockets should be. The table is saved per screen size.
3. **Balls**: non-felt mask → distance transform peaks + Hough circles, merged and validated. Ball radius is estimated from isolated balls. Each ball is classified by white / black / coloured pixel share (cue, 8, solid, stripe).
4. **Shots**: for every legal target ball and pocket it tries direct shots (ghost ball), banks (object ball off one cushion, mirror image) and kicks (cue ball off one cushion). Every path is checked for collisions. Shots are ranked by how much cue-aim error they tolerate (pocket width, distances, cut angle) with penalties for banks, kicks, scratches and near-max power.
5. **Power**: a friction model (v² = 2·a·d) with cushion and ball-collision energy loss, calibrated by "full power = N table lengths" in settings.

## Manual mode

Edit on the floating panel: drag balls, pockets or table edges, add/delete balls, set the cue ball, change ball type, or tap a ball/pocket to force the planner to use it.

## Build

GitHub Actions builds on every push to `main` (`.github/workflows/build.yml`), runs the planner unit tests and publishes the APK as a release.
Locally: open in Android Studio and Build → Build APK.
