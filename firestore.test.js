const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_student_123";
const BOB_UID = "bob_student_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function validProfile(uid, ts = new Date(Date.now() - 1000)) {
  return {
    userId: uid,
    displayName: "Alex Chen",
    streakDays: 3,
    totalReviewed: 42,
    lastStudyDayKey: "2026-11-21",
    createdAt: ts,
    updatedAt: ts,
  };
}

function validDeck(uid, ts = new Date(Date.now() - 1000)) {
  return {
    userId: uid,
    title: "Organic Chemistry — Alkenes",
    subject: "Chemistry",
    description: "Reaction mechanisms forged from lecture whiteboard.",
    sourceType: "PHOTO",
    accentIndex: 1,
    cardCount: 6,
    masteredCount: 2,
    createdAt: ts,
    updatedAt: ts,
  };
}

function validCard(uid, deckId, ts = new Date(Date.now() - 1000)) {
  return {
    userId: uid,
    deckId: deckId,
    cardType: "MULTIPLE_CHOICE",
    question: "Which rule predicts the major product of HBr addition to an asymmetric alkene?",
    answer: "Markovnikov's rule",
    explanation: "The proton adds to the carbon with more hydrogens to form the more stable carbocation.",
    hint: "Think about carbocation stability.",
    options: [
      "Markovnikov's rule",
      "Zaitsev's rule",
      "Hund's rule",
      "Le Chatelier's principle",
    ],
    correctOptionIndex: 0,
    easeFactor: 2.5,
    intervalDays: 1,
    repetitions: 0,
    dueAtMillis: Date.now(),
    lastReviewedAtMillis: 0,
    createdAt: ts,
    updatedAt: ts,
  };
}

test("Unauthenticated user: cannot read or write profile, decks, or cards", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID)));
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("decks").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("cards").get());
});

test("UserProfile: owner can create/read/update; rejects cross-user and ghost fields", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID, ts)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());

  // Partial stats update succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      streakDays: 4,
      totalReviewed: 43,
      lastStudyDayKey: "2026-11-22",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Cross-user read/update rejected
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).update({ streakDays: 999 }));

  // Ghost field & immutable createdAt rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Future timestamp rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      streakDays: 5,
      updatedAt: new Date(Date.now() + 60000),
    })
  );
});

test("StudyDeck & Flashcard: atomic batch create, list, review update, edit, and delete", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  // Atomic batch creating deck + card (exercises existsAfter parent check!)
  const batch = aliceDb.batch();
  const deckRef = aliceDb.collection("users").doc(ALICE_UID).collection("decks").doc("deck_chem_1");
  const cardRef = aliceDb.collection("users").doc(ALICE_UID).collection("cards").doc("card_chem_1");
  batch.set(deckRef, validDeck(ALICE_UID, ts));
  batch.set(cardRef, validCard(ALICE_UID, "deck_chem_1", ts));
  await assertSucceeds(batch.commit());

  // List queries matching Kotlin repository
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("decks").get());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("cards").get());

  // Partial spaced-repetition review update on card
  await assertSucceeds(
    cardRef.update({
      easeFactor: 2.6,
      intervalDays: 6,
      repetitions: 2,
      dueAtMillis: Date.now() + 86400000 * 6,
      lastReviewedAtMillis: Date.now(),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Partial edit of card content
  await assertSucceeds(
    cardRef.update({
      cardType: "FILL_IN_BLANK",
      question: "___ rule predicts the major product of HBr addition.",
      answer: "Markovnikov's",
      explanation: "Forms the more stable carbocation intermediate.",
      hint: "Russian chemist",
      options: [],
      correctOptionIndex: -1,
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // Deck counter sync update
  await assertSucceeds(
    deckRef.update({
      cardCount: 1,
      masteredCount: 1,
      updatedAt: new Date(Date.now() - 300),
    })
  );

  // Deck metadata edit
  await assertSucceeds(
    deckRef.update({
      title: "Organic Chemistry II",
      subject: "Chemistry",
      description: "Updated notes",
      accentIndex: 2,
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Owner can delete card and deck
  await assertSucceeds(cardRef.delete());
  await assertSucceeds(deckRef.delete());
});

test("Flashcard security bounds: rejects orphan card, cross-user access, extra id field, invalid easeFactor", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  // Orphan card referencing non-existent deck rejected
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("cards")
      .doc("orphan_card")
      .set(validCard(ALICE_UID, "missing_deck", ts))
  );

  // Create valid deck
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("decks")
      .doc("deck_1")
      .set(validDeck(ALICE_UID, ts))
  );

  // Card with extra "id" field (@DocumentId violation) rejected
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("cards")
      .doc("card_bad_id")
      .set({
        id: "card_bad_id",
        ...validCard(ALICE_UID, "deck_1", ts),
      })
  );

  // Card with invalid easeFactor (< 1.3) rejected
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("cards")
      .doc("card_bad_ease")
      .set({
        ...validCard(ALICE_UID, "deck_1", ts),
        easeFactor: 0.5,
      })
  );

  // Valid card creation succeeds
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("cards")
      .doc("card_ok")
      .set(validCard(ALICE_UID, "deck_1", ts))
  );

  // Immutable deckId mutation rejected
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("cards")
      .doc("card_ok")
      .update({
        deckId: "another_deck",
        updatedAt: new Date(Date.now() - 500),
      })
  );

  // Bob cannot list, read, update, or delete Alice's decks or cards
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("decks").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("cards").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("cards").doc("card_ok").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("cards").doc("card_ok").delete());
});
