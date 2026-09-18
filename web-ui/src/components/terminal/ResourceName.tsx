import type { Resource } from '../../api/resources';

/**
 * A resource's display name with the styling the game gives it. Colour and emphasis reach the terminal as
 * spans, so a name written with formatting codes reads the way it does in game instead of showing them.
 */
export function ResourceName({ resource }: { resource: Pick<Resource, 'name' | 'nameSpans'> }) {
  if (resource.nameSpans === null || resource.nameSpans.length === 0) {
    return <>{resource.name}</>;
  }
  return (
    <>
      {resource.nameSpans.map((span, index) => (
        <span
          key={index}
          style={{
            color: span.color ?? undefined,
            fontWeight: span.bold ? 700 : undefined,
            fontStyle: span.italic ? 'italic' : undefined,
            textDecoration:
              [span.underlined ? 'underline' : null, span.strikethrough ? 'line-through' : null]
                .filter(Boolean)
                .join(' ') || undefined,
          }}
        >
          {span.text}
        </span>
      ))}
    </>
  );
}
