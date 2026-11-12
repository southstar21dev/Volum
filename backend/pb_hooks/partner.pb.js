routerAdd(
  "GET",
  "/api/volum/partner",
  (e) => {
    const toUser = (record) => ({
      id: record.id,
      name: record.getString("name"),
      email: record.email(),
    });

    const me = e.app.findRecordById("users", e.auth.id);
    const partnerId = me.getString("partner");

    let partner = null;
    if (partnerId) {
      try {
        partner = e.app.findRecordById("users", partnerId);
      } catch (_) {
        partner = null;
      }
    }

    let activeWorkout = null;
    if (partner) {
      const active = e.app.findRecordsByFilter(
        "workouts",
        "owner = {:owner} && status = 'active' && deleted = false",
        "-startedAt",
        1,
        0,
        { owner: partner.id },
      );
      if (active.length > 0) {
        activeWorkout = {
          id: active[0].id,
          routineName: active[0].getString("routineName"),
          startedAt: active[0].getString("startedAt"),
        };
      }
    }

    const incoming = e.app
      .findRecordsByFilter("pair_requests", "target = {:id}", "", 100, 0, { id: e.auth.id })
      .map((request) => ({
        id: request.id,
        from: toUser(e.app.findRecordById("users", request.getString("requester"))),
      }));

    const outgoing = e.app
      .findRecordsByFilter("pair_requests", "requester = {:id}", "", 100, 0, { id: e.auth.id })
      .map((request) => ({
        id: request.id,
        to: toUser(e.app.findRecordById("users", request.getString("target"))),
      }));

    return e.json(200, {
      partner: partner ? toUser(partner) : null,
      activeWorkout,
      incoming,
      outgoing,
    });
  },
  $apis.requireAuth(),
);

routerAdd(
  "POST",
  "/api/volum/partner/request",
  (e) => {
    const toUser = (record) => ({
      id: record.id,
      name: record.getString("name"),
      email: record.email(),
    });

    const body = e.requestInfo().body || {};
    const email = String(body.email || "").trim().toLowerCase();
    if (!email) {
      throw new BadRequestError("Email is required");
    }

    let target;
    try {
      target = e.app.findAuthRecordByEmail("users", email);
    } catch (_) {
      throw new BadRequestError("No user found with that email");
    }

    if (target.id === e.auth.id) {
      throw new BadRequestError("You cannot pair with yourself");
    }

    const me = e.app.findRecordById("users", e.auth.id);
    if (me.getString("partner")) {
      throw new BadRequestError("You are already paired. Unpair first");
    }
    if (target.getString("partner")) {
      throw new BadRequestError("That account is already paired");
    }

    const outgoing = e.app.findRecordsByFilter("pair_requests", "requester = {:id}", "", 100, 0, {
      id: e.auth.id,
    });
    const incoming = e.app.findRecordsByFilter("pair_requests", "target = {:id}", "", 100, 0, {
      id: e.auth.id,
    });

    const mineToThem = outgoing.find((row) => row.getString("target") === target.id);
    if (mineToThem) {
      return e.json(200, { id: mineToThem.id, to: toUser(target) });
    }

    const theirsToMe = incoming.find((row) => row.getString("requester") === target.id);
    if (theirsToMe) {
      throw new BadRequestError("They already sent you a request — accept it in Settings");
    }

    const collection = e.app.findCollectionByNameOrId("pair_requests");
    const request = new Record(collection);
    request.set("requester", e.auth.id);
    request.set("target", target.id);
    e.app.save(request);

    return e.json(200, { id: request.id, to: toUser(target) });
  },
  $apis.requireAuth(),
);

routerAdd(
  "POST",
  "/api/volum/partner/accept",
  (e) => {
    const toUser = (record) => ({
      id: record.id,
      name: record.getString("name"),
      email: record.email(),
    });

    const body = e.requestInfo().body || {};
    const id = String(body.id || "");
    if (!id) {
      throw new BadRequestError("id is required");
    }

    let request;
    try {
      request = e.app.findRecordById("pair_requests", id);
    } catch (_) {
      throw new NotFoundError("Request not found");
    }
    if (request.getString("target") !== e.auth.id) {
      throw new ForbiddenError("Only the request recipient can accept it");
    }

    const me = e.app.findRecordById("users", e.auth.id);
    const requester = e.app.findRecordById("users", request.getString("requester"));
    if (me.getString("partner")) {
      throw new BadRequestError("You are already paired. Unpair first");
    }
    if (requester.getString("partner")) {
      throw new BadRequestError("That account is already paired");
    }

    me.set("partner", requester.id);
    requester.set("partner", me.id);
    e.app.save(me);
    e.app.save(requester);

    const outgoing = e.app.findRecordsByFilter("pair_requests", "requester = {:id}", "", 100, 0, {
      id: me.id,
    });
    const incoming = e.app.findRecordsByFilter("pair_requests", "target = {:id}", "", 100, 0, {
      id: me.id,
    });
    for (const row of outgoing) {
      if (row.getString("target") === requester.id) {
        e.app.delete(row);
      }
    }
    for (const row of incoming) {
      if (row.getString("requester") === requester.id) {
        e.app.delete(row);
      }
    }

    return e.json(200, { partner: toUser(requester) });
  },
  $apis.requireAuth(),
);

routerAdd(
  "POST",
  "/api/volum/partner/decline",
  (e) => {
    const body = e.requestInfo().body || {};
    const id = String(body.id || "");
    if (!id) {
      throw new BadRequestError("id is required");
    }

    let request;
    try {
      request = e.app.findRecordById("pair_requests", id);
    } catch (_) {
      throw new NotFoundError("Request not found");
    }
    if (request.getString("target") !== e.auth.id) {
      throw new ForbiddenError("Only the request recipient can decline it");
    }

    e.app.delete(request);
    return e.noContent(204);
  },
  $apis.requireAuth(),
);

routerAdd(
  "DELETE",
  "/api/volum/partner/request",
  (e) => {
    const body = e.requestInfo().body || {};
    const id = String(body.id || e.request.url.query().get("id") || "");
    if (!id) {
      throw new BadRequestError("id is required");
    }

    let request;
    try {
      request = e.app.findRecordById("pair_requests", id);
    } catch (_) {
      throw new NotFoundError("Request not found");
    }
    if (request.getString("requester") !== e.auth.id) {
      throw new ForbiddenError("Only the sender can cancel it");
    }

    e.app.delete(request);
    return e.noContent(204);
  },
  $apis.requireAuth(),
);

routerAdd(
  "DELETE",
  "/api/volum/partner",
  (e) => {
    const me = e.app.findRecordById("users", e.auth.id);
    const partnerId = me.getString("partner");
    if (partnerId) {
      let partner = null;
      try {
        partner = e.app.findRecordById("users", partnerId);
      } catch (_) {
        partner = null;
      }
      me.set("partner", "");
      e.app.save(me);
      if (partner && partner.getString("partner") === me.id) {
        partner.set("partner", "");
        e.app.save(partner);
      }

      const outgoing = e.app.findRecordsByFilter("pair_requests", "requester = {:id}", "", 100, 0, {
        id: me.id,
      });
      const incoming = e.app.findRecordsByFilter("pair_requests", "target = {:id}", "", 100, 0, {
        id: me.id,
      });
      for (const row of outgoing) {
        if (row.getString("target") === partnerId) {
          e.app.delete(row);
        }
      }
      for (const row of incoming) {
        if (row.getString("requester") === partnerId) {
          e.app.delete(row);
        }
      }
    }
    return e.noContent(204);
  },
  $apis.requireAuth(),
);

// Applies one set mutation to an active workout inside a transaction, so a
// partner's edit can't race the owner's sync and silently drop either change.
routerAdd(
  "POST",
  "/api/volum/partner/workout/{id}/mutate",
  (e) => {
    const { applyMutation } = require(`${__hooks}/workout_merge.js`);
    const id = e.request.pathValue("id");
    const body = e.requestInfo().body || {};

    // Errors thrown inside the transaction callback may lose their HTTP
    // status crossing back from Go, so the callback only reports an outcome.
    let outcome = "ok";
    let saved = null;
    e.app.runInTransaction((txApp) => {
      let workout;
      try {
        workout = txApp.findRecordById("workouts", id);
      } catch (_) {
        outcome = "ended";
        return;
      }
      if (workout.getString("status") !== "active" || workout.getBool("deleted")) {
        outcome = "ended";
        return;
      }

      const ownerId = workout.getString("owner");
      if (ownerId !== e.auth.id) {
        const me = txApp.findRecordById("users", e.auth.id);
        const owner = txApp.findRecordById("users", ownerId);
        if (me.getString("partner") !== ownerId || owner.getString("partner") !== e.auth.id) {
          outcome = "forbidden";
          return;
        }
      }

      const now = new Date().toISOString();
      const entries = applyMutation(JSON.parse(workout.getString("entries") || "[]"), body.mutation, now);
      if (!entries) {
        outcome = "invalid";
        return;
      }
      workout.set("entries", entries);
      if (body.mutation.kind === "removeEntry") {
        const removed = JSON.parse(workout.getString("removedEntryIds") || "[]") || [];
        workout.set("removedEntryIds", removed.concat([body.mutation.entryId]));
      }
      workout.set("updatedAt", now);
      txApp.save(workout);
      saved = workout;
    });

    if (outcome === "ended") throw new NotFoundError("Session ended");
    if (outcome === "forbidden") throw new ForbiddenError("You can only edit your partner's workout");
    if (outcome === "invalid") throw new BadRequestError("Invalid change");
    return e.json(200, saved);
  },
  $apis.requireAuth(),
);

onRecordCreateRequest(
  (e) => {
    if (!e.hasSuperuserAuth()) {
      e.record.set("partner", "");
    }
    e.next();
  },
  "users",
);

onRecordUpdateRequest(
  (e) => {
    if (!e.hasSuperuserAuth()) {
      e.record.set("partner", e.record.original().get("partner"));
    }
    e.next();
  },
  "users",
);

onRecordUpdateRequest(
  (e) => {
    if (e.hasSuperuserAuth()) {
      return e.next();
    }
    const original = e.record.original();
    const owner = original.getString("owner");
    if (owner && e.auth && e.auth.id !== owner) {
      e.record.set("owner", owner);
      e.record.set("routineId", original.getString("routineId"));
      e.record.set("routineName", original.getString("routineName"));
      e.record.set("startedAt", original.getString("startedAt"));
      e.record.set("finishedAt", original.getString("finishedAt"));
      e.record.set("status", original.getString("status"));
      e.record.set("deleted", original.getBool("deleted"));
    }

    // Sync pushes the whole record. Merge it per exercise and set with what's
    // stored, so partner edits made since that snapshot survive.
    //
    // The snapshot time is the server `updated` of the copy the push was based on
    // (`syncBase`, sent by the app; not a record field). The pushed `updatedAt` is
    // the owner's last local edit, which is later than a partner's set added before
    // that edit but after the owner's last pull, so it would drop that set. Older
    // apps don't send `syncBase` and keep the old behaviour.
    if (original.getString("status") === "active") {
      const { mergeEntries } = require(`${__hooks}/workout_merge.js`);
      const stored = JSON.parse(original.getString("entries") || "[]");
      const incoming = JSON.parse(e.record.getString("entries") || "[]");
      const removed = Array.from(
        new Set(
          (JSON.parse(original.getString("removedEntryIds") || "[]") || []).concat(
            JSON.parse(e.record.getString("removedEntryIds") || "[]") || [],
          ),
        ),
      );
      const syncBase = (e.requestInfo().body || {}).syncBase;
      const snapshotAt =
        typeof syncBase === "string" && !Number.isNaN(Date.parse(syncBase.replace(" ", "T")))
          ? syncBase
          : e.record.getString("updatedAt");
      e.record.set("entries", mergeEntries(stored, incoming, snapshotAt, removed));
      e.record.set("removedEntryIds", removed);
    }
    e.next();
  },
  "workouts",
);
