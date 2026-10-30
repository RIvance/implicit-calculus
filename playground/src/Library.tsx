import { useState } from 'react';
import { Badge, Button, Group, SegmentedControl, TextInput } from '@mantine/core';
import { ArrowUpRight, Clock3, FileCode2, Plus, Search, Trash2 } from 'lucide-react';
import { examples, type Example } from './examples';
import type { DocumentState, HistoryEntry } from './document';

export function Library({
  selected,
  onOpen,
  onNew,
  history,
  clearHistory,
}: {
  selected: string;
  onOpen: (document: DocumentState) => void;
  onNew: () => void;
  history: HistoryEntry[];
  clearHistory: () => void;
}) {
  const [tab, setTab] = useState('examples');
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('all');
  const matches = examples.filter(
    (example) =>
      (filter === 'all' || example.expectation === filter) &&
      `${example.path} ${example.title} ${example.source}`.toLowerCase().includes(search.toLowerCase()),
  );
  function open(example: Example) {
    onOpen({ name: example.path, source: example.source });
  }
  return (
    <div className="library">
      <div className="library-heading">
        <span>WORKSPACE</span>
        <Button size="compact-xs" variant="subtle" leftSection={<Plus size={13} />} onClick={onNew}>
          New
        </Button>
      </div>
      <SegmentedControl
        fullWidth
        size="xs"
        value={tab}
        onChange={setTab}
        data={[
          { value: 'examples', label: 'Examples' },
          { value: 'history', label: 'History' },
        ]}
      />
      {tab === 'examples' ? (
        <>
          <TextInput
            mt="md"
            mb="sm"
            size="xs"
            aria-label="Search examples"
            placeholder="Search programs…"
            value={search}
            onChange={(event) => setSearch(event.currentTarget.value)}
            leftSection={<Search size={13} />}
          />
          <div className="library-filters">
            {(['all', 'result', 'error'] as const).map((value) => (
              <button key={value} className={filter === value ? 'active' : ''} onClick={() => setFilter(value)}>
                {value === 'all' ? 'All' : value === 'result' ? 'Values' : 'Errors'}
              </button>
            ))}
            <span>{matches.length}</span>
          </div>
          <div className="library-list">
            {matches.map((example) => (
              <button
                key={example.path}
                className={`library-item ${selected === example.path ? 'selected' : ''}`}
                onClick={() => open(example)}
                title={example.path}
              >
                <FileCode2 size={16} />
                <span>
                  <strong>{example.title.replace('errors/', '')}</strong>
                  <small>{example.expectation === 'error' ? 'Expected diagnostic' : 'Runnable program'}</small>
                </span>
                <span className={`example-dot ${example.expectation}`} />
              </button>
            ))}
          </div>
          <div className="library-note">
            <ArrowUpRight size={13} />
            <span>Loaded from the project’s examples directory.</span>
          </div>
        </>
      ) : (
        <>
          <Group justify="space-between" my="md">
            <Badge color="gray" variant="light">
              {history.length} saved runs
            </Badge>
            <Button
              size="compact-xs"
              color="gray"
              variant="subtle"
              aria-label="Clear run history"
              onClick={clearHistory}
            >
              <Trash2 size={13} />
            </Button>
          </Group>
          <div className="library-list">
            {history.map((entry) => (
              <button key={entry.id} className="library-item" onClick={() => onOpen(entry)}>
                <Clock3 size={15} />
                <span>
                  <strong>{entry.name}</strong>
                  <small>{entry.summary}</small>
                  <small>{new Date(entry.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</small>
                </span>
                <span className={`example-dot ${entry.ok ? 'result' : 'error'}`} />
              </button>
            ))}
            {history.length === 0 && (
              <p className="muted small">Completed runs appear here. Select one to restore its source.</p>
            )}
          </div>
        </>
      )}
    </div>
  );
}
