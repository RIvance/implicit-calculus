import { expect, test, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';

const program = `instance seed: Int = 73
instance wrap[A](implicit input: A): { item: A } = { item = input }
summon[{ item: Int }]`;

async function edit(page: Page, source: string) {
  await page.locator('.monaco-editor .view-lines').click({ position: { x: 100, y: 30 } });
  await page.keyboard.press('ControlOrMeta+A');
  await page.keyboard.insertText(source);
}

test('real synthesis supports graph, constraint, model, trace, export, and baseline inspection', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('./');
  await expect(page.getByText('Local solver ready', { exact: true })).toBeVisible();
  await edit(page, program);
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('{item = 73}');
  await expect(page.getByTestId('evidence-graph')).toBeVisible();
  await page.getByTestId('evidence-graph').getByText('wrap', { exact: true }).click();
  await expect(page.getByText('Spine z₁')).toBeVisible();
  await page.getByRole('button', { name: 'Inspect constraints' }).click();
  await expect(page.getByRole('combobox', { name: 'Constraint spine' })).toHaveValue('z₁');
  await page.getByRole('textbox', { name: 'Search constraints' }).fill('implicit parameter');
  await expect(page.locator('.constraint-card')).toHaveCount(1);
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export complete SMT problem' }).click();
  const exported = await download;
  expect(exported.suggestedFilename()).toMatch(/\.smt2$/);
  const query = await readFile((await exported.path())!, 'utf8');
  expect(query).toContain('(define-funs-rec');
  expect(query).toContain('(check-sat)');
  expect(query).toContain('(get-model)');
  await page.getByRole('tab', { name: 'Model', exact: true }).click();
  await expect(page.getByText('Accepted model', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Previous attempt' }).click();
  await expect(page.getByText('No model for this attempt')).toBeVisible();
  await page.getByRole('tab', { name: 'Trace', exact: true }).click();
  await expect(page.locator('.trace-attempt')).toHaveCount(2);
  await page.getByRole('button', { name: 'Play search playback' }).click();
  await expect(page.locator('.trace-selected')).toContainText('Attempt 2');
  await page.getByRole('button', { name: 'Compare search' }).click();
  await expect(page.locator('.comparison-strip')).toContainText('Exact: MissingInstance');
  await expect(page.locator('.comparison-strip')).toContainText('SMT: resolved');
  await page.getByRole('tab', { name: 'Terms', exact: true }).click();
  await expect(page.locator('.term-inspector')).toContainText('seed');
  await page.screenshot({ path: '.cache/screenshots/terms.png', fullPage: true });
  expect(errors).toEqual([]);
});

test('editing, sharing, history, diagnostics, and cancellation use the current source', async ({ page, context }) => {
  await page.goto('./');
  await expect(page.getByText('Local solver ready', { exact: true })).toBeVisible();
  await edit(page, program.replace('73', '89'));
  await page.keyboard.press('ControlOrMeta+Enter');
  await expect(page.getByTestId('program-value')).toHaveText('{item = 89}');
  await page.getByRole('button', { name: 'Share program', exact: true }).click();
  const url = await page.getByRole('textbox', { name: 'Program share link' }).inputValue();
  const shared = await context.newPage();
  await shared.goto(url);
  await shared.getByRole('button', { name: 'Run program' }).click();
  await expect(shared.getByTestId('program-value')).toHaveText('{item = 89}');
  await shared.close();
  await page.keyboard.press('Escape');
  await edit(page, 'def loop(value: Int): Int = loop(value)\nloop(0)');
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByRole('button', { name: 'Stop', exact: true })).toBeVisible();
  await expect(page.getByText('Evaluating', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Stop', exact: true }).click();
  await expect(page.locator('.diagnostic')).toContainText('Execution cancelled');
  await edit(page, 'let value =');
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.locator('.diagnostic')).toContainText('Parse');
  await expect(page.locator('.diagnostic')).toContainText('Line');
  await edit(page, '21');
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('21');
  await page.getByText('History', { exact: true }).click();
  await expect(page.locator('.library-list')).toContainText('{item = 89}');
});

test('the workbench remains usable on narrow screens and in dark mode', async ({ page }) => {
  await page.setViewportSize({ width: 420, height: 900 });
  await page.goto('./');
  await expect(page.getByText('Local solver ready', { exact: true })).toBeAttached();
  await page.getByRole('button', { name: 'Programs', exact: true }).click();
  await page.getByRole('textbox', { name: 'Search examples' }).fill('synthesized totals');
  await page.getByRole('button', { name: /synthesized totals/ }).click();
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('{bytes = 6144, requests = 150}');
  await page.getByRole('button', { name: 'Toggle color scheme' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-mantine-color-scheme', 'dark');
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(420);
  await page.screenshot({ path: '.cache/screenshots/mobile.png', fullPage: true });
});

test('search bounds and execution deadlines control real computations', async ({ page }) => {
  await page.goto('./');
  await expect(page.getByText('Local solver ready', { exact: true })).toBeVisible();
  await edit(page, program);
  await page.getByRole('button', { name: 'Search settings', exact: true }).click();
  await page.getByRole('textbox', { name: 'Construction cost N' }).fill('3');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.locator('.diagnostic')).toContainText('SynthesisLimit');
  await expect(page.locator('.diagnostic')).toContainText('cost 3');
  await page.getByRole('button', { name: 'Search settings', exact: true }).click();
  await page.getByRole('textbox', { name: 'Construction cost N' }).fill('4');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('{item = 73}');
  await edit(page, 'def loop(value: Int): Int = loop(value)\nloop(0)');
  await expect(page.getByText('previous source', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Search settings', exact: true }).click();
  await page.getByRole('textbox', { name: 'Execution timeout (ms)' }).fill('1000');
  await page.getByRole('switch', { name: 'Evaluate after compilation' }).uncheck();
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await page.getByRole('button', { name: 'Compile' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('Compiled successfully');
  await page.getByRole('button', { name: 'Search settings', exact: true }).click();
  await page.getByRole('switch', { name: 'Evaluate after compilation' }).check();
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.locator('.diagnostic')).toContainText('ExecutionTimeout');
  await edit(page, '37');
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('37');
  await expect(page.getByText('draft saved locally', { exact: false })).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('37');
});

test('static hosting enables the solver without server headers and survives reloading', async ({
  page,
  request,
  baseURL,
}) => {
  const response = await request.get('./');
  expect(response.headers()['cross-origin-opener-policy']).toBeUndefined();
  expect(response.headers()['cross-origin-embedder-policy']).toBeUndefined();
  await page.goto('./');
  await expect(page.getByText('Local solver ready', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => crossOriginIsolated)).toBe(true);
  expect(await page.evaluate(() => navigator.serviceWorker.controller?.scriptURL)).toBe(
    new URL('coi-serviceworker.js', baseURL).href,
  );
  await edit(page, program);
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('{item = 73}');
  await page.reload();
  await expect(page.getByText('Local solver ready', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('{item = 73}');
});
