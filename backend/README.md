# Backend

The PocketBase server the apps sync with (`volum.pocketbaseUrl` in `gradle.properties`).

- `pb_migrations/`: collections, fields and API rules. PocketBase applies new ones on start.
  Exercises are one shared library: every signed-in account can read every exercise (by design,
  `1759600008_exercises_global_library.js`). Routines and workouts stay private to the owner and
  their partner.
- `pb_hooks/partner.pb.js`: pairing routes, live partner sessions and the merge of an owner's
  whole-workout push with the stored copy.
- `pb_hooks/workout_merge.js`: the merge itself. `shared/.../data/WorkoutMerge.kt` mirrors it; keep
  the two in sync.
- `Dockerfile`, `start.sh`: the deployed image. Build it with this folder as the context.

Environment for the container: `PB_ADMIN_EMAIL` and `PB_ADMIN_PASSWORD` (superuser, upserted on
start), `PB_ORIGINS` (optional CORS origins). Data lives in `/pb_data`; mount it as a volume.

## Run locally

Download the PocketBase release named in the `Dockerfile`, then from this folder:

```sh
pocketbase superuser upsert admin@example.test <password> --dir=pb_data
pocketbase serve --http=127.0.0.1:8090 --dir=pb_data --hooksDir=pb_hooks --migrationsDir=pb_migrations
```

Build the apps against it with `-Pvolum.pocketbaseUrl=http://127.0.0.1:8090`. Android blocks
cleartext HTTP, so a local build also needs a debug-only network security config that allows
`127.0.0.1`.

Pushes that only change this folder don't run app CI or cut an app release.
