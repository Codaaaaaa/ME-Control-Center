import { describe, expect, it } from 'vitest';
import enUs from '../i18n/locales/en_us.json';
import { ALERT_TYPES, alertEventSchema, eventMessage } from './alerts';

describe('alerts', () => {
  it('have a message for every type and kind, with amounts in the resource unit', () => {
    const messages = enUs.alerts.message as Record<string, Record<string, string>>;
    for (const type of ALERT_TYPES) {
      for (const kind of ['TRIGGERED', 'RESOLVED'] as const) {
        const event = alertEventSchema.parse({
          id: 1, ruleId: 'r', networkId: 'n', networkName: 'Base', type, kind, at: '2026-09-18T12:00:00Z',
          resource: null, value: 2500, threshold: 5000, orderId: null,
        });
        const message = eventMessage(event, (raw) => `${raw / 1000} B`);
        const [, , typeKey, kindKey] = message.key.split('.');
        expect(messages[typeKey!]?.[kindKey!], message.key).toBeTypeOf('string');
        const percent = ['ENERGY_LOW', 'RESOURCE_DROP', 'RESOURCE_RISE'].includes(type);
        expect(message.values.value).toBe(percent ? '2500' : '2.5 B');
        expect(message.values.threshold).toBe(type === 'RESOURCE_BELOW' || type === 'RESOURCE_ABOVE' ? '5 B' : '5000');
      }
    }
  });
});
