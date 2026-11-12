migrate((app) => {
  const workouts = app.findCollectionByNameOrId("workouts");
  // Exercises a partner removed from an active workout, so the owner's next sync doesn't bring them back.
  workouts.fields.add(new JSONField({ name: "removedEntryIds", maxSize: 200000 }));
  app.save(workouts);
}, (app) => {
  const workouts = app.findCollectionByNameOrId("workouts");
  workouts.fields.removeByName("removedEntryIds");
  app.save(workouts);
});
