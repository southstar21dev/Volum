migrate((app) => {
  let users;
  try {
    users = app.findCollectionByNameOrId("users");
  } catch (_) {
    users = new Collection({
      type: "auth",
      name: "users",
      fields: [{ type: "text", name: "name", required: false, max: 100 }],
    });
  }

  users.listRule = "id = @request.auth.id";
  users.viewRule = "id = @request.auth.id";
  users.createRule = "";
  users.updateRule = "id = @request.auth.id";
  users.deleteRule = "id = @request.auth.id";
  users.authToken.duration = 2592000;

  app.save(users);
}, (app) => {
  const users = app.findCollectionByNameOrId("users");
  users.authToken.duration = 1209600;
  app.save(users);
});
