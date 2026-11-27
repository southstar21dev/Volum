migrate((app) => {
  const exercises = app.findCollectionByNameOrId("exercises");
  exercises.fields.add(new BoolField({ name: "assisted" }));
  app.save(exercises);
}, (app) => {
  const exercises = app.findCollectionByNameOrId("exercises");
  exercises.fields.removeByName("assisted");
  app.save(exercises);
});
