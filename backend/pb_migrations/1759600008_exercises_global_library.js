migrate((app) => {
  const exercises = app.findCollectionByNameOrId("exercises");
  exercises.listRule = '@request.auth.id != ""';
  exercises.viewRule = '@request.auth.id != ""';
  app.save(exercises);
}, (app) => {
  const exercises = app.findCollectionByNameOrId("exercises");
  const partnerRule =
    "owner = @request.auth.id || (owner.partner = @request.auth.id && @request.auth.partner = owner)";
  exercises.listRule = partnerRule;
  exercises.viewRule = partnerRule;
  app.save(exercises);
});
