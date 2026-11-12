migrate((app) => {
  const cutoff = Date.now() - 60 * 60 * 1000;
  const active = app.findRecordsByFilter("workouts", "status = 'active'", "", 500, 0);
  for (const record of active) {
    const started = Date.parse(record.getString("startedAt"));
    if (!Number.isNaN(started) && started > cutoff) {
      continue;
    }
    const finishedAt = record.getString("updatedAt") || record.getString("startedAt");
    record.set("status", "completed");
    record.set("finishedAt", finishedAt);
    app.save(record);
  }
}, (app) => {});
