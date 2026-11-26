migrate((app) => {
  // The owner can't be handed to someone else on update, and a record can only be created for
  // yourself (a bare `owner` in updateRule reads the stored record, not the request body).
  const ownerUpdate =
    "owner = @request.auth.id && (@request.body.owner:isset = false || @request.body.owner = owner)";
  const ownerCreate = '@request.auth.id != "" && @request.body.owner = @request.auth.id';

  for (const name of ["exercises", "routines", "workouts"]) {
    const collection = app.findCollectionByNameOrId(name);
    collection.createRule = ownerCreate;
    collection.updateRule = ownerUpdate;
    app.save(collection);
  }

  // A partner edits an active workout only through POST /api/volum/partner/workout/{id}/mutate
  // (updateRule is the owner now), and never sees one that was deleted.
  const activePartnerRule =
    'owner = @request.auth.id || (owner.partner = @request.auth.id && @request.auth.partner = owner && status = "active" && deleted = false)';
  const workouts = app.findCollectionByNameOrId("workouts");
  workouts.listRule = activePartnerRule;
  workouts.viewRule = activePartnerRule;
  workouts.addIndex("idx_workouts_owner_updated_id", false, "owner, updated, id", "");
  workouts.addIndex("idx_workouts_owner_status_deleted_started", false, "owner, status, deleted, startedAt", "");
  app.save(workouts);

  const routines = app.findCollectionByNameOrId("routines");
  routines.addIndex("idx_routines_owner_updated_id", false, "owner, updated, id", "");
  app.save(routines);

  const exercises = app.findCollectionByNameOrId("exercises");
  exercises.addIndex("idx_exercises_updated_id", false, "updated, id", "");
  app.save(exercises);

  const users = app.findCollectionByNameOrId("users");
  users.addIndex("idx_users_partner", false, "partner", "");
  app.save(users);
}, (app) => {
  const own = "owner = @request.auth.id";

  const workouts = app.findCollectionByNameOrId("workouts");
  const activePartnerRule =
    'owner = @request.auth.id || (owner.partner = @request.auth.id && @request.auth.partner = owner && status = "active")';
  workouts.listRule = activePartnerRule;
  workouts.viewRule = activePartnerRule;
  workouts.updateRule = activePartnerRule;
  workouts.createRule = own;
  workouts.removeIndex("idx_workouts_owner_updated_id");
  workouts.removeIndex("idx_workouts_owner_status_deleted_started");
  app.save(workouts);

  const routines = app.findCollectionByNameOrId("routines");
  routines.createRule = own;
  routines.updateRule = own;
  routines.removeIndex("idx_routines_owner_updated_id");
  app.save(routines);

  const exercises = app.findCollectionByNameOrId("exercises");
  exercises.createRule = own;
  exercises.updateRule = own;
  exercises.removeIndex("idx_exercises_updated_id");
  app.save(exercises);

  const users = app.findCollectionByNameOrId("users");
  users.removeIndex("idx_users_partner");
  app.save(users);
});
