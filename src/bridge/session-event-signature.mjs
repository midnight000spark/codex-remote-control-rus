import { userMessageContentSignature } from './common.mjs';

export function pruneHydratedEvents(hydratedEvents, storedEvents) {
  if (hydratedEvents.length === 0) {
    return [];
  }

  const storedAssistantDeltaTurns = new Set(
    storedEvents
      .filter((event) => event?.event === 'item/agentMessage/delta')
      .map((event) => event?.turn_id ?? '')
      .filter(Boolean),
  );
  const hydratedAssistantCounts = new Map();
  for (const event of hydratedEvents) {
    if (event?.event !== 'item/completed' || event?.payload?.item?.type !== 'agentMessage') {
      continue;
    }
    const turnId = event?.turn_id ?? '';
    if (!turnId) continue;
    hydratedAssistantCounts.set(turnId, (hydratedAssistantCounts.get(turnId) ?? 0) + 1);
  }

  return hydratedEvents.filter((event) => {
    if (event?.event !== 'item/completed' || event?.payload?.item?.type !== 'agentMessage') {
      return true;
    }
    const turnId = event?.turn_id ?? '';
    if (!turnId || !storedAssistantDeltaTurns.has(turnId)) {
      return true;
    }
    return (hydratedAssistantCounts.get(turnId) ?? 0) > 1;
  });
}

export function eventSignature(event) {
  const payload = event?.payload ?? {};
  if (event?.event === 'turn/input') {
    return [
      event?.event ?? '',
      event?.turn_id ?? '',
      payload?.itemId ?? payload?.item_id ?? '',
      userMessageContentSignature(payload?.input, payload?.text),
    ].join('|');
  }
  if (event?.event === 'item/completed' || event?.event === 'item/started') {
    const item = canonicalizeItemForSignature(payload?.item ?? {});
    return [
      event?.event ?? '',
      event?.turn_id ?? '',
      item?.id ?? '',
      item?.type ?? '',
      JSON.stringify(item),
    ].join('|');
  }
  if (event?.event === 'item/commandExecution/requestApproval') {
    return [
      event?.event ?? '',
      event?.turn_id ?? '',
      event?.request_id ?? '',
      payload?.itemId ?? '',
    ].join('|');
  }
  if (event?.event === 'turn/completed') {
    return [
      event?.event ?? '',
      event?.turn_id ?? '',
      payload?.status ?? payload?.turn?.status ?? '',
      payload?.turn?.error?.message ?? payload?.error?.message ?? '',
    ].join('|');
  }
  return [
    event?.event ?? '',
    event?.turn_id ?? '',
    payload?.itemId ?? '',
    payload?.text ?? '',
    payload?.delta ?? '',
    payload?.status ?? '',
    payload?.message ?? '',
    payload?.error?.message ?? '',
    payload?.item?.id ?? '',
    payload?.item?.type ?? '',
  ].join('|');
}

export function eventDedupeSignatures(event) {
  const signatures = [eventSignature(event)];
  const turnId = event?.turn_id ?? '';
  const payload = event?.payload ?? {};
  if (event?.event === 'turn/input') {
    signatures.push([
      'content',
      event.event,
      turnId,
      userMessageContentSignature(payload?.input, payload?.text),
    ].join('|'));
  } else if (event?.event === 'item/completed' && payload?.item?.type === 'agentMessage') {
    signatures.push([
      'content',
      event.event,
      turnId,
      payload.item.type,
      String(payload.item.text ?? '').trim(),
    ].join('|'));
  }
  return signatures;
}

function canonicalizeItemForSignature(item) {
  if (!item || typeof item !== 'object' || Array.isArray(item)) {
    return item;
  }
  // Only top-level keys are dropped and the result is stringified right away,
  // so a shallow copy is enough and much cheaper than a deep clone.
  const normalized = { ...item };
  delete normalized.phase;
  delete normalized.memoryCitation;
  return normalized;
}
