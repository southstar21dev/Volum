// Transaction callbacks below only record an outcome: an error thrown inside runInTransaction can
// lose its HTTP status crossing back from Go, so the route throws it afterwards. Handlers run in
// their own runtimes and see no top-level values, so constants live inside them.

routerAdd(
  "GET",
  "/api/volum/partner",
  (e) => {
    const { findUser } = require(`${__hooks}/partner_util.js`);
    const toUser = (record) => ({
      id: record.id,
      name: record.getString("name"),
      email: record.email(),
      avatar: record.getString("avatar"),
    });

    const me = findUser(e.app, e.auth.id);
    if (!me) {
      throw new ForbiddenError("Account not found");
    }
    const partner = findUser(e.app, me.getString("partner"));

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

    const incomingRows = e.app.findRecordsByFilter("pair_requests", "target = {:id}", "", 100, 0, { id: e.auth.id });
    const outgoingRows = e.app.findRecordsByFilter("pair_requests", "requester = {:id}", "", 100, 0, { id: e.auth.id });

    // One query for everyone referenced, instead of one per request.
    const ids = [];
    for (const row of incomingRows) ids.push(row.getString("requester"));
    for (const row of outgoingRows) ids.push(row.getString("target"));
    const users = {};
    if (ids.length > 0) {
      for (const user of e.app.findRecordsByIds("users", ids)) users[user.id] = user;
    }

    const incoming = incomingRows
      .filter((row) => users[row.getString("requester")])
      .map((row) => ({ id: row.id, from: toUser(users[row.getString("requester")]) }));
    // Someone who has not accepted yet is shown by the email you typed only: no id, name or photo.
    const outgoing = outgoingRows
      .filter((row) => users[row.getString("target")])
      .map((row) => ({
        id: row.id,
        to: { id: "", name: "", email: users[row.getString("target")].email(), avatar: "" },
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
    const { findOne } = require(`${__hooks}/partner_util.js`);
    const MAX_OUTGOING_REQUESTS = 10;
    const body = e.requestInfo().body || {};
    const email = String(body.email || "").trim().toLowerCase();
    if (!email) {
      throw new BadRequestError("Email is required");
    }

    let outcome = "ok";
    let requestId = "";
    e.app.runInTransaction((txApp) => {
      let target;
      try {
        target = txApp.findAuthRecordByEmail("users", email);
      } catch (_) {
        outcome = "nouser";
        return;
      }
      if (target.id === e.auth.id) {
        outcome = "self";
        return;
      }

      let me;
      try {
        me = txApp.findRecordById("users", e.auth.id);
      } catch (_) {
        outcome = "nouser";
        return;
      }
      if (me.getString("partner")) {
        outcome = "mepaired";
        return;
      }
      if (target.getString("partner")) {
        outcome = "targetpaired";
        return;
      }

      const mineToThem = findOne(txApp, "pair_requests", "requester = {:me} && target = {:them}", {
        me: me.id,
        them: target.id,
      });
      if (mineToThem) {
        requestId = mineToThem.id;
        return;
      }
      if (findOne(txApp, "pair_requests", "requester = {:them} && target = {:me}", { me: me.id, them: target.id })) {
        outcome = "theyasked";
        return;
      }
      if (txApp.countRecords("pair_requests", $dbx.hashExp({ requester: me.id })) >= MAX_OUTGOING_REQUESTS) {
        outcome = "toomany";
        return;
      }

      const request = new Record(txApp.findCollectionByNameOrId("pair_requests"));
      request.set("requester", me.id);
      request.set("target", target.id);
      txApp.save(request);
      requestId = request.id;
    });

    if (outcome === "nouser") throw new BadRequestError("No user found with that email");
    if (outcome === "self") throw new BadRequestError("You cannot pair with yourself");
    if (outcome === "mepaired") throw new BadRequestError("You are already paired. Unpair first");
    if (outcome === "targetpaired") throw new BadRequestError("That account is already paired");
    if (outcome === "theyasked") throw new BadRequestError("They already sent you a request. Accept theirs instead");
    if (outcome === "toomany") throw new BadRequestError("Too many pending requests. Cancel one first");
    return e.json(200, { id: requestId, to: { id: "", name: "", email, avatar: "" } });
  },
  $apis.requireAuth(),
);

routerAdd(
  "POST",
  "/api/volum/partner/accept",
  (e) => {
    const { findOne } = require(`${__hooks}/partner_util.js`);
    const toUser = (record) => ({
      id: record.id,
      name: record.getString("name"),
      email: record.email(),
      avatar: record.getString("avatar"),
    });

    const body = e.requestInfo().body || {};
    const id = String(body.id || "");
    if (!id) {
      throw new BadRequestError("id is required");
    }

    // Everything is read again inside the transaction, so two accepts (or an accept and an
    // unpair) can't both pass the "not paired" checks and leave one-sided partner pointers.
    let outcome = "ok";
    let partnerRecord = null;
    e.app.runInTransaction((txApp) => {
      let request;
      try {
        request = txApp.findRecordById("pair_requests", id);
      } catch (_) {
        outcome = "notfound";
        return;
      }
      if (request.getString("target") !== e.auth.id) {
        outcome = "forbidden";
        return;
      }

      let me;
      let requester;
      try {
        me = txApp.findRecordById("users", e.auth.id);
        requester = txApp.findRecordById("users", request.getString("requester"));
      } catch (_) {
        outcome = "notfound";
        return;
      }
      if (me.getString("partner")) {
        outcome = "mepaired";
        return;
      }
      if (requester.getString("partner")) {
        outcome = "targetpaired";
        return;
      }

      me.set("partner", requester.id);
      requester.set("partner", me.id);
      txApp.save(me);
      txApp.save(requester);

      txApp.delete(request);
      const reverse = findOne(txApp, "pair_requests", "requester = {:me} && target = {:them}", {
        me: me.id,
        them: requester.id,
      });
      if (reverse) {
        txApp.delete(reverse);
      }
      partnerRecord = requester;
    });

    if (outcome === "notfound") throw new NotFoundError("Request not found");
    if (outcome === "forbidden") throw new ForbiddenError("Only the request recipient can accept it");
    if (outcome === "mepaired") throw new BadRequestError("You are already paired. Unpair first");
    if (outcome === "targetpaired") throw new BadRequestError("That account is already paired");
    return e.json(200, { partner: toUser(partnerRecord) });
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
    const { findOne } = require(`${__hooks}/partner_util.js`);
    e.app.runInTransaction((txApp) => {
      const me = txApp.findRecordById("users", e.auth.id);
      const partnerId = me.getString("partner");
      if (!partnerId) return;

      let partner = null;
      try {
        partner = txApp.findRecordById("users", partnerId);
      } catch (_) {
        partner = null;
      }
      me.set("partner", "");
      txApp.save(me);
      if (partner && partner.getString("partner") === me.id) {
        partner.set("partner", "");
        txApp.save(partner);
      }

      // Any request left between the two of them, in either direction.
      for (const [from, to] of [[me.id, partnerId], [partnerId, me.id]]) {
        const row = findOne(txApp, "pair_requests", "requester = {:from} && target = {:to}", { from, to });
        if (row) txApp.delete(row);
      }
    });
    return e.noContent(204);
  },
  $apis.requireAuth(),
);

// Applies a partner's mutation (or, as `mutations`, a batch of up to 50 applied in order, all or
// nothing) to an active workout inside a transaction, so an edit can't race the owner's sync and
// silently drop either change.
routerAdd(
  "POST",
  "/api/volum/partner/workout/{id}/mutate",
  (e) => {
    const { parseList, findUser, mutationsOf, applyMutations } = require(`${__hooks}/partner_util.js`);
    const id = e.request.pathValue("id");
    const mutations = mutationsOf(e.requestInfo().body);
    if (!mutations) {
      throw new BadRequestError("Invalid change");
    }

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
        const me = findUser(txApp, e.auth.id);
        const owner = findUser(txApp, ownerId);
        if (!me || !owner || me.getString("partner") !== ownerId || owner.getString("partner") !== e.auth.id) {
          outcome = "forbidden";
          return;
        }
      }

      const now = new Date().toISOString();
      const result = applyMutations(
        parseList(workout.getString("entries")),
        parseList(workout.getString("removedEntryIds")),
        mutations,
        now,
      );
      if (!result) {
        outcome = "invalid";
        return;
      }
      if (result.changed) {
        workout.set("entries", result.entries);
        if (result.removedEntryIds !== null) workout.set("removedEntryIds", result.removedEntryIds);
        workout.set("updatedAt", now);
        txApp.save(workout);
      }
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

// The owner never changes through the API (the update rules say so too): put the stored one back.
onRecordUpdateRequest(
  (e) => {
    if (!e.hasSuperuserAuth()) {
      e.record.set("owner", e.record.original().getString("owner"));
    }
    e.next();
  },
  "exercises",
  "routines",
);

onRecordUpdateRequest(
  (e) => {
    if (e.hasSuperuserAuth()) {
      return e.next();
    }
    const { parseList } = require(`${__hooks}/partner_util.js`);
    const original = e.record.original();
    const owner = original.getString("owner");
    e.record.set("owner", owner);
    if (owner && e.auth && e.auth.id !== owner) {
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
      const stored = parseList(original.getString("entries"));
      const incoming = parseList(e.record.getString("entries"));
      const removed = Array.from(
        new Set(parseList(original.getString("removedEntryIds")).concat(parseList(e.record.getString("removedEntryIds")))),
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
