migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  const collection = new Collection({
    type: "base",
    name: "routines",
    listRule: "owner = @request.auth.id",
    viewRule: "owner = @request.auth.id",
    createRule: "owner = @request.auth.id",
    updateRule: "owner = @request.auth.id",
    deleteRule: "owner = @request.auth.id",
    fields: [
      { type: "relation", name: "owner", required: true, collectionId: users.id, maxSelect: 1 },
      { type: "text", name: "name", required: true, max: 120 },
      { type: "json", name: "items", required: true, maxSize: 2000000 },
      { type: "date", name: "updatedAt", required: true },
      { type: "bool", name: "deleted" },
      { type: "autodate", name: "updated", onCreate: true, onUpdate: true },
    ],
    indexes: ["CREATE INDEX idx_routines_updated ON routines (updated)"],
  });
  app.save(collection);
}, (app) => {
  app.delete(app.findCollectionByNameOrId("routines"));
});
