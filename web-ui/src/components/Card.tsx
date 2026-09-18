import type { ReactNode } from 'react';

export type Tone = 'success' | 'warning' | 'danger' | 'neutral' | 'accent';

export function Card({
  title,
  badge,
  children,
  className,
}: {
  title: ReactNode;
  badge?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={className ? `card ${className}` : 'card'}>
      <header className="card-header">
        <h2 className="card-title">{title}</h2>
        {badge}
      </header>
      {children}
    </section>
  );
}

export function Badge({ tone, children, title }: { tone: Tone; children: ReactNode; title?: string }) {
  return (
    <span className={`badge badge-${tone}`} title={title}>
      {children}
    </span>
  );
}

export function Fields({ children }: { children: ReactNode }) {
  return <dl className="fields">{children}</dl>;
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="field">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}
