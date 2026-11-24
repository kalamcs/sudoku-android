// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import assert from 'node:assert/strict'
import test from 'node:test'

const project = 'demo-sudoku-feedback'
const authHost = process.env.FIREBASE_AUTH_EMULATOR_HOST
const firestoreHost = process.env.FIRESTORE_EMULATOR_HOST
const database = `projects/${project}/databases/(default)/documents`
const url = `http://${firestoreHost}/v1/${database}`

test('feedback rules accept valid anonymous creates and keep submissions private', async () => {
  assert.ok(authHost && firestoreHost, 'Start the Auth and Firestore emulators before running this test')
  const signUp = await fetch(
    `http://${authHost}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-key`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ returnSecureToken: true }),
    },
  )
  assert.ok(signUp.ok, await signUp.clone().text())
  const { idToken } = await signUp.json()
  const feedback = { type: 'bug_report', message: 'Example report', appVersion: '1.1' }

  async function submit(id, entry, token = idToken) {
    const response = await fetch(`${url}:commit`, {
      method: 'POST',
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        writes: [{
          update: {
            name: `${database}/feedback/${id}`,
            fields: Object.fromEntries(Object.entries(entry).map(([key, value]) => [key, { stringValue: value }])),
          },
          updateTransforms: [{ fieldPath: 'createdAt', setToServerValue: 'REQUEST_TIME' }],
        }],
      }),
    })
    return { status: response.status, body: await response.text() }
  }

  const first = crypto.randomUUID()
  const result = await submit(first, feedback)
  assert.equal(result.status, 200, result.body)
  const contact = await submit(crypto.randomUUID(), { ...feedback, email: 'tester@example.com' })
  assert.equal(contact.status, 200, contact.body)

  for (const invalid of [
    { ...feedback, message: ' '.repeat(5) },
    { ...feedback, message: 'x'.repeat(1001) },
    { ...feedback, type: 'invalid' },
    { ...feedback, email: 'invalid' },
    { ...feedback, unexpected: 'value' },
  ]) {
    assert.equal((await submit(crypto.randomUUID(), invalid)).status, 403)
  }
  assert.equal((await submit(crypto.randomUUID(), feedback, '')).status, 403)
  assert.equal((await submit(first, { ...feedback, message: 'Overwriting prior feedback' })).status, 403)
  const headers = { Authorization: `Bearer ${idToken}` }
  assert.equal((await fetch(`${url}/feedback/${first}`, { headers })).status, 403)
  assert.equal((await fetch(`${url}/feedback`, { headers })).status, 403)
})
