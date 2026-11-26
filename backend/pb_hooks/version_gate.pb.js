// Turns away app builds older than the minimum set in the `app_config` collection (edit its one
// record in the dashboard; the change applies to the next request). The app shows an "update
// required" screen on a 426; builds from before that screen show the message as a sync error and
// keep their unsynced changes queued until they are updated.
//
// The record is cached in the app store (shared by every hook runtime) as JSON, so a request does
// not hit SQLite for it; any write to app_config drops the cached copy.
routerUse((e) => {
  if (e.request.url.path.indexOf("/api/") !== 0) {
    return e.next();
  }

  let required = null;
  try {
    const { requiredVersion } = require(`${__hooks}/version_gate.js`);
    let cached = e.app.store().get("volumAppConfig");
    if (typeof cached !== "string") {
      const rows = e.app.findRecordsByFilter("app_config", "id != ''", "", 1, 0);
      cached = JSON.stringify(
        rows.length > 0
          ? {
              minIos: rows[0].getString("minIos"),
              minAndroid: rows[0].getString("minAndroid"),
              blockLegacy: rows[0].getBool("blockLegacy"),
            }
          : null,
      );
      e.app.store().set("volumAppConfig", cached);
    }
    const config = JSON.parse(cached);
    if (config) {
      required = requiredVersion(
        {
          version: e.request.header.get("X-App-Version"),
          platform: e.request.header.get("X-App-Platform"),
          userAgent: e.request.header.get("User-Agent"),
        },
        config,
      );
    }
  } catch (_) {
    // A missing collection or a bad value lets everyone through rather than no one.
    required = null;
  }

  if (required === null) {
    return e.next();
  }
  return e.json(426, {
    status: 426,
    message: "This version of Volum is out of date. Update it to keep syncing.",
    data: { minVersion: required },
  });
});

// Drop the cached config whenever the record changes, so the next request reads the new one.
onRecordAfterCreateSuccess((e) => {
  e.app.store().remove("volumAppConfig");
  e.next();
}, "app_config");
onRecordAfterUpdateSuccess((e) => {
  e.app.store().remove("volumAppConfig");
  e.next();
}, "app_config");
onRecordAfterDeleteSuccess((e) => {
  e.app.store().remove("volumAppConfig");
  e.next();
}, "app_config");
