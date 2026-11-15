migrate((app) => {
  // One record, edited in the dashboard only (no API rules): the oldest app versions the server
  // still talks to. Empty means no minimum. See pb_hooks/version_gate.pb.js.
  const config = new Collection({
    type: "base",
    name: "app_config",
    listRule: null,
    viewRule: null,
    createRule: null,
    updateRule: null,
    deleteRule: null,
    fields: [
      { type: "text", name: "minIos" },
      { type: "text", name: "minAndroid" },
      // Refuses builds from before the app sent its version (iOS and Android alike).
      { type: "bool", name: "blockLegacy" },
    ],
  });
  app.save(config);
  app.save(new Record(config));
}, (app) => {
  app.delete(app.findCollectionByNameOrId("app_config"));
});
