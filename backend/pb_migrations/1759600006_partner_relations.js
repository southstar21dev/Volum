migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  users.fields.add(
    new RelationField({
      name: "partner",
      required: false,
      collectionId: users.id,
      maxSelect: 1,
    }),
  );
  app.save(users);

  const pairRequests = new Collection({
    type: "base",
    name: "pair_requests",
    listRule: null,
    viewRule: null,
    createRule: null,
    updateRule: null,
    deleteRule: null,
    fields: [
      {
        type: "relation",
        name: "requester",
        required: true,
        collectionId: users.id,
        maxSelect: 1,
        cascadeDelete: true,
      },
      {
        type: "relation",
        name: "target",
        required: true,
        collectionId: users.id,
        maxSelect: 1,
        cascadeDelete: true,
      },
    ],
    indexes: [
      "CREATE INDEX idx_pair_requests_target ON pair_requests (target)",
      "CREATE UNIQUE INDEX idx_pair_requests_pair ON pair_requests (requester, target)",
    ],
  });
  app.save(pairRequests);

  const sharedRule =
    "owner = @request.auth.id || (owner.partner = @request.auth.id && @request.auth.partner = owner)";
  const activeWorkoutRule =
    'owner = @request.auth.id || (owner.partner = @request.auth.id && @request.auth.partner = owner && status = "active")';

  const exercises = app.findCollectionByNameOrId("exercises");
  exercises.listRule = sharedRule;
  exercises.viewRule = sharedRule;
  app.save(exercises);

  const routines = app.findCollectionByNameOrId("routines");
  routines.listRule = sharedRule;
  routines.viewRule = sharedRule;
  app.save(routines);

  const workouts = app.findCollectionByNameOrId("workouts");
  workouts.listRule = activeWorkoutRule;
  workouts.viewRule = activeWorkoutRule;
  workouts.updateRule = activeWorkoutRule;
  app.save(workouts);
}, (app) => {
  const exercises = app.findCollectionByNameOrId("exercises");
  exercises.listRule = "owner = @request.auth.id";
  exercises.viewRule = "owner = @request.auth.id";
  app.save(exercises);

  const routines = app.findCollectionByNameOrId("routines");
  routines.listRule =
    "owner = @request.auth.id || routine_shares_via_routine.user ?= @request.auth.id";
  routines.viewRule =
    "owner = @request.auth.id || routine_shares_via_routine.user ?= @request.auth.id";
  app.save(routines);

  const workouts = app.findCollectionByNameOrId("workouts");
  workouts.listRule = "owner = @request.auth.id";
  workouts.viewRule = "owner = @request.auth.id";
  workouts.updateRule = "owner = @request.auth.id";
  app.save(workouts);

  app.delete(app.findCollectionByNameOrId("pair_requests"));

  const users = app.findCollectionByNameOrId("users");
  users.fields.removeByName("partner");
  app.save(users);
});
