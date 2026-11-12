migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  const routines = app.findCollectionByNameOrId("routines");
  const collection = new Collection({
    type: "base",
    name: "routine_shares",
    listRule: "user = @request.auth.id || routine.owner = @request.auth.id",
    viewRule: "user = @request.auth.id || routine.owner = @request.auth.id",
    createRule: null,
    updateRule: null,
    deleteRule: null,
    fields: [
      {
        type: "relation",
        name: "routine",
        required: true,
        collectionId: routines.id,
        maxSelect: 1,
        cascadeDelete: true,
      },
      {
        type: "relation",
        name: "user",
        required: true,
        collectionId: users.id,
        maxSelect: 1,
        cascadeDelete: true,
      },
      { type: "email", name: "email", required: true },
    ],
    indexes: [
      "CREATE INDEX idx_routine_shares_user ON routine_shares (user)",
      "CREATE UNIQUE INDEX idx_routine_shares_routine_user ON routine_shares (routine, user)",
    ],
  });
  app.save(collection);

  routines.listRule =
    "owner = @request.auth.id || routine_shares_via_routine.user ?= @request.auth.id";
  routines.viewRule =
    "owner = @request.auth.id || routine_shares_via_routine.user ?= @request.auth.id";
  app.save(routines);
}, (app) => {
  const routines = app.findCollectionByNameOrId("routines");
  routines.listRule = "owner = @request.auth.id";
  routines.viewRule = "owner = @request.auth.id";
  app.save(routines);
  app.delete(app.findCollectionByNameOrId("routine_shares"));
});
