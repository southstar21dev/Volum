migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  const collection = new Collection({
    type: "base",
    name: "workouts",
    listRule: "owner = @request.auth.id",
    viewRule: "owner = @request.auth.id",
    createRule: "owner = @request.auth.id",
    updateRule: "owner = @request.auth.id",
    deleteRule: "owner = @request.auth.id",
    fields: [
      { type: "relation", name: "owner", required: true, collectionId: users.id, maxSelect: 1 },
      { type: "text", name: "routineId", required: false, max: 15 },
      { type: "text", name: "routineName", required: true, max: 120 },
      { type: "date", name: "startedAt", required: true },
      { type: "date", name: "finishedAt", required: false },
      {
        type: "select",
        name: "status",
        required: true,
        maxSelect: 1,
        values: ["active", "completed"],
      },
      { type: "json", name: "entries", required: true, maxSize: 5000000 },
      { type: "date", name: "updatedAt", required: true },
      { type: "bool", name: "deleted" },
      { type: "autodate", name: "updated", onCreate: true, onUpdate: true },
    ],
    indexes: ["CREATE INDEX idx_workouts_updated ON workouts (updated)"],
  });
  app.save(collection);
}, (app) => {
  app.delete(app.findCollectionByNameOrId("workouts"));
});
