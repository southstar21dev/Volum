migrate((app) => {
  const settings = app.settings();
  settings.meta.appName = "Volum";
  app.save(settings);
}, (app) => {
  const settings = app.settings();
  settings.meta.appName = "Acme";
  app.save(settings);
});
