migrate((app) => {
  const cutoff = Date.now() - 60 * 60 * 1000;
  // Collect every active workout first (a page at a time), then change them: saving one drops it
  // out of the filter and would shift the pages under us.
  const active = [];
  for (let offset = 0; ; offset += 500) {
    const page = app.findRecordsByFilter("workouts", "status = 'active'", "", 500, offset);
    active.push(...page);
    if (page.length < 500) break;
  }
  for (const record of active) {
    // PocketBase dates read "2026-10-20 10:00:00.000Z"; make them ISO before parsing.
    const started = Date.parse(record.getString("startedAt").replace(" ", "T"));
    // An unreadable start is left alone rather than ending a session that may be live.
    if (Number.isNaN(started) || started > cutoff) {
      continue;
    }
    const finishedAt = record.getString("updatedAt") || record.getString("startedAt");
    record.set("status", "completed");
    record.set("finishedAt", finishedAt);
    app.save(record);
  }
}, (app) => {});
