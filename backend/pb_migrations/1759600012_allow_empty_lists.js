// An active workout can lose its last exercise and a routine its last item; PocketBase treats a
// required JSON field holding [] as blank and rejects the save, which left the change stuck in the
// app's outbox. The app reads a missing or empty list as empty.
migrate((app) => {
  const workouts = app.findCollectionByNameOrId("workouts");
  workouts.fields.getByName("entries").required = false;
  app.save(workouts);

  const routines = app.findCollectionByNameOrId("routines");
  routines.fields.getByName("items").required = false;
  app.save(routines);
}, (app) => {
  const workouts = app.findCollectionByNameOrId("workouts");
  workouts.fields.getByName("entries").required = true;
  app.save(workouts);

  const routines = app.findCollectionByNameOrId("routines");
  routines.fields.getByName("items").required = true;
  app.save(routines);
});
