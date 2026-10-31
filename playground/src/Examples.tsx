import { useState } from 'react';
import { TextInput } from '@mantine/core';
import { FileCode2, Search } from 'lucide-react';
import { examples } from './examples';
import type { DocumentState } from './document';

export function Examples({ selected, onOpen }: { selected: string; onOpen: (document: DocumentState) => void }) {
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState<'all' | 'result' | 'error'>('all');
  const matches = examples.filter(
    (example) =>
      (filter === 'all' || example.expectation === filter) &&
      `${example.path} ${example.title} ${example.source}`.toLowerCase().includes(search.toLowerCase()),
  );
  return (
    <div className="examples">
      <TextInput
        data-autofocus
        size="sm"
        aria-label="Search examples"
        placeholder="Search examples…"
        value={search}
        onChange={(event) => setSearch(event.currentTarget.value)}
        leftSection={<Search size={15} />}
      />
      <div className="example-filters">
        {(['all', 'result', 'error'] as const).map((value) => (
          <button
            key={value}
            className={filter === value ? 'active' : ''}
            aria-pressed={filter === value}
            onClick={() => setFilter(value)}
          >
            {value === 'all' ? 'All' : value === 'result' ? 'Values' : 'Errors'}
          </button>
        ))}
        <span>{matches.length} examples</span>
      </div>
      <div className="example-list">
        {matches.map((example) => (
          <button
            key={example.path}
            className={`example-item ${selected === example.path ? 'selected' : ''}`}
            onClick={() => onOpen({ name: example.path, source: example.source })}
            title={example.path}
          >
            <FileCode2 size={17} />
            <span>
              <strong>{example.title.replace('errors/', '')}</strong>
              <small>{example.expectation === 'error' ? 'Expected diagnostic' : 'Runnable program'}</small>
            </span>
            <span className={`example-dot ${example.expectation}`} />
          </button>
        ))}
        {matches.length === 0 && <p className="muted small">No examples match this search.</p>}
      </div>
    </div>
  );
}
