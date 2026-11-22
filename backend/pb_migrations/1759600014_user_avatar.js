migrate((app) => {
  // The profile photo. Not protected: a partner can't view the users record (viewRule is the
  // owner only), so they load it by URL from the name the partner routes hand out. PocketBase adds a
  // random suffix to every stored file name, so the URL can't be guessed from the user id.
  const users = app.findCollectionByNameOrId("users");
  users.fields.add(
    new FileField({
      name: "avatar",
      maxSelect: 1,
      maxSize: 2 * 1024 * 1024,
      mimeTypes: ["image/jpeg", "image/png", "image/webp"],
      thumbs: ["128x128", "256x256"],
      protected: false,
    }),
  );
  app.save(users);
}, (app) => {
  const users = app.findCollectionByNameOrId("users");
  users.fields.removeByName("avatar");
  app.save(users);
});
