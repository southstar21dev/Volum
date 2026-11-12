migrate((app) => {
  const exercises = app.findCollectionByNameOrId("exercises");
  exercises.fields.add(new BoolField({ name: "timed" }));
  app.save(exercises);
}, (app) => {
  const exercises = app.findCollectionByNameOrId("exercises");
  exercises.fields.removeByName("timed");
  app.save(exercises);
});
