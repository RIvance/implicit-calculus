export interface Example {
  path: string;
  title: string;
  source: string;
  expectation: 'result' | 'error';
  expected: string;
}

const files = import.meta.glob<string>('../../examples/**/*.imp', { query: '?raw', import: 'default', eager: true });

export const examples: Example[] = Object.entries(files)
  .map(([file, source]) => {
    const heading = /^\/\/ (result|error): (.+)\r?\n/.exec(source);
    if (!heading) throw new Error(`Missing result or error heading in ${file}`);
    const path = file.replace('../../examples/', '');
    return {
      path,
      title: path.replace(/\.imp$/, '').replaceAll('-', ' '),
      source,
      expectation: heading[1] as 'result' | 'error',
      expected: heading[2],
    };
  })
  .sort((left, right) => left.path.localeCompare(right.path));
