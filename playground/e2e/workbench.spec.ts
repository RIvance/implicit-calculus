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

async function wordColors(page: Page) {
  return page.locator('.monaco-editor .view-line span[class]').evaluateAll((spans) =>
    spans.flatMap((span) =>
      Array.from((span.textContent ?? '').matchAll(/[\p{L}_$][\p{L}\p{N}_$]*/gu)).map(([word]) => ({
        word,
        color: getComputedStyle(span).color,
      })),
    ),
  );
}

test('type positions share one color, distinct from term names and keywords in both themes', async ({ page }) => {
  await page.goto('./');
  await expect(page.getByText('Z3 backend ready', { exact: true })).toBeVisible();
  const source = `trait ToString[A] = {
  toString: A -> String
}
def display[A where ToString[A]](x: A): String = x.toString
display[Int](1)`;
  await edit(page, source);
  for (const theme of ['light', 'dark']) {
    if (theme === 'dark') await page.getByRole('button', { name: 'Toggle color scheme' }).click();
    await expect
      .poll(async () => {
        const words = await wordColors(page);
        const typeColor = words.find(({ word }) => word === 'Int')?.color;
        const types = words.filter(({ word }) => ['A', 'ToString', 'String', 'Int'].includes(word));
        const others = words.filter(({ word }) => ['display', 'x', 'toString', 'def', 'trait', 'where'].includes(word));
        return (
          types.length === 10 &&
          types.every(({ color }) => color === typeColor) &&
          others.every(({ color }) => color !== typeColor)
        );
      })
      .toBe(true);
  }
  await edit(page, 'let A: Int = 1 in A');
  await expect
    .poll(async () => {
      const words = await wordColors(page);
      const typeColor = words.find(({ word }) => word === 'Int')?.color;
      return words.filter(({ word }) => word === 'A').every(({ color }) => color !== typeColor);
    })
    .toBe(true);
});

test('named unresolved goals select the matching synthesis request and lexical context', async ({ page }) => {
  await page.goto('./');
  await expect(page.getByText('Z3 backend ready', { exact: true })).toBeVisible();
  await edit(
    page,
    `instance seed: Int = 11
let first = summon[Int]
given second: Int = 22
{ zebra = summon[Int], alpha = first, result = summon[Int] }`,
  );
  const first = page.getByRole('button', { name: 'Inspect goal ?first : Int', exact: true });
  const zebra = page.getByRole('button', { name: 'Inspect goal ?zebra : Int', exact: true });
  await expect(page.getByRole('region', { name: 'Unresolved term' })).toContainText('Run a program');
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('{alpha = 11, result = 22, zebra = 22}');
  await zebra.hover();
  const preview = page.getByRole('tooltip', { name: '?zebra : Int ?zebra ≔ second', exact: true });
  await expect(preview).toBeVisible();
  await expect(first).toHaveAttribute('aria-pressed', 'true');
  await expect(zebra).toHaveText('?zebra');
  await preview.hover();
  await expect(preview).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(preview).not.toBeVisible();
  await zebra.hover();
  await expect(preview).toBeVisible();
  await page.mouse.down();
  await expect(zebra).toHaveText('?zebra');
  await expect(first).toHaveAttribute('aria-pressed', 'true');
  await page.mouse.up();
  await expect(preview).not.toBeVisible();
  await expect(zebra).toHaveText('?zebra : Int');
  await expect(first).toHaveText('?first');
  await expect(zebra).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.goal-judgment')).toHaveText('Δ ⊢ ?zebra : Int');
  await expect(page.locator('.context-count')).toHaveText('2 instances in scope');
  await first.click();
  await expect(first).toHaveText('?first : Int');
  await expect(zebra).toHaveText('?zebra');
  await expect(first).toHaveAttribute('aria-pressed', 'true');
  await expect(zebra).toHaveAttribute('aria-pressed', 'false');
  await expect(page.locator('.goal-judgment')).toHaveText('Δ ⊢ ?first : Int');
  await expect(page.locator('.context-count')).toHaveText('1 instances in scope');
  await expect(page.locator('.goal-result')).toHaveText('?first ≔ seed');
  await page.getByText('Unresolved term', { exact: true }).hover();
  await first.hover();
  await expect(page.getByRole('tooltip', { name: '?first : Int ?first ≔ seed', exact: true })).toBeVisible();
  await page.screenshot({ path: '.cache/screenshots/unresolved-goals.png', fullPage: true });
});

test('goal markers retain successes, explain failures, and distinguish goals not reached', async ({ page }) => {
  await page.goto('./');
  await expect(page.getByText('Z3 backend ready', { exact: true })).toBeVisible();
  await edit(
    page,
    `instance seed: Int = 11
let found = summon[Int]
let missing = summon[String]
let skipped = summon[Bool]
found`,
  );
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.locator('.diagnostic')).toContainText('MissingInstance');
  const found = page.getByRole('button', { name: 'Inspect goal ?found : Int', exact: true });
  const missing = page.getByRole('button', { name: 'Inspect goal ?missing : String', exact: true });
  const skipped = page.getByRole('button', { name: 'Inspect goal ?skipped : Bool', exact: true });
  await found.click();
  await expect(page.locator('.goal-result')).toHaveText('?found ≔ seed');
  await missing.click();
  await expect(page.locator('.goal-judgment')).toHaveText('Δ ⊢ ?missing : String');
  await page.getByText('Unresolved term', { exact: true }).hover();
  await missing.hover();
  await expect(
    page.getByRole('tooltip', { name: '?missing : String No evidence found for String.', exact: true }),
  ).toBeVisible();
  await expect(skipped).toHaveAttribute('aria-disabled', 'true');
  await skipped.hover();
  await expect(
    page.getByRole('tooltip', { name: '?skipped : Bool Synthesis has not reached this goal.', exact: true }),
  ).toBeVisible();
});

test('keyboard previews preserve selection until activation and respect reduced motion', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('./');
  await expect(page.getByText('Z3 backend ready', { exact: true })).toBeVisible();
  await edit(page, 'instance seed: Int = 11\nlet first = summon[Int]\nlet second = summon[Int]\nfirst + second');
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('22');
  const first = page.getByRole('button', { name: 'Inspect goal ?first : Int', exact: true });
  const second = page.getByRole('button', { name: 'Inspect goal ?second : Int', exact: true });
  await first.focus();
  await page.keyboard.press('Tab');
  // The binding's fold control precedes its goal in keyboard order.
  await expect(page.getByRole('button', { name: 'Fold second', exact: true })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(second).toBeFocused();
  await expect(second).toHaveText('?second');
  await expect(first).toHaveAttribute('aria-pressed', 'true');
  const preview = page.getByRole('tooltip', { name: '?second : Int ?second ≔ seed', exact: true });
  await expect(preview).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(preview).not.toBeVisible();
  await expect(second).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(second).toHaveAttribute('aria-pressed', 'true');
  await expect(second).toHaveText('?second : Int');
  await expect(first).toHaveText('?first');
  await expect(page.locator('.goal-result')).toHaveText('?second ≔ seed');
  expect(await second.evaluate((element) => element.getAnimations({ subtree: true }).length)).toBe(0);
  await first.focus();
  await page.keyboard.press('Space');
  await expect(first).toHaveAttribute('aria-pressed', 'true');
  await expect(first).toBeFocused();
  await expect(page.getByRole('tooltip')).toHaveCount(0);
});

test('switching multiline annotations preserves the clicked goal position in a scrolled term', async ({ page }) => {
  await page.goto('./');
  await expect(page.getByText('Z3 backend ready', { exact: true })).toBeVisible();
  await edit(
    page,
    `type Evidence = { alpha: Int, beta: Int, gamma: Int, delta: Int, epsilon: Int }
instance evidence: Evidence = { alpha = 1, beta = 2, gamma = 3, delta = 4, epsilon = 5 }
${Array.from({ length: 16 }, (_, index) => `let before${index} = ${index}`).join('\n')}
let first = summon[Evidence]
let second = summon[Evidence]
${Array.from({ length: 16 }, (_, index) => `let after${index} = ${index}`).join('\n')}
second.alpha`,
  );
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('1');
  const first = page.getByRole('button', { name: /^Inspect goal \?first :/ });
  const second = page.getByRole('button', { name: /^Inspect goal \?second :/ });
  await expect.poll(() => first.innerText()).toContain('\n');
  await second.scrollIntoViewIfNeeded();
  const before = (await second.boundingBox())!;
  expect(await page.locator('.unresolved-code').evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
  await second.click();
  await expect(second).toHaveAttribute('aria-pressed', 'true');
  await expect(first).toHaveText('?first');
  await expect.poll(() => second.innerText()).toContain('\n');
  await expect.poll(async () => Math.abs((await second.boundingBox())!.y - before.y)).toBeLessThan(2);
  await expect(page.locator('.goal-result')).toHaveText('?second ≔ evidence');
});

test('three columns keep compact goals, folding, and example selection connected', async ({ page }) => {
  await page.goto('./');
  await expect(page.getByText('Z3 backend ready', { exact: true })).toBeVisible();
  await page.locator('.editor-title').getByRole('button', { name: 'Examples', exact: true }).click();
  const examples = page.getByRole('dialog', { name: 'Examples', exact: true });
  await examples.getByRole('textbox', { name: 'Search examples' }).fill('display');
  await examples.getByRole('button', { name: 'display Runnable program', exact: true }).click();
  await expect(examples).not.toBeVisible();
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('Student(Ada)');
  const source = (await page.locator('.source-panel').boundingBox())!;
  const term = (await page.locator('.term-panel').boundingBox())!;
  const inspector = (await page.locator('.research-panel').boundingBox())!;
  expect(term.x).toBeGreaterThanOrEqual(source.x + source.width);
  expect(inspector.x).toBeGreaterThanOrEqual(term.x + term.width);
  expect(term.height).toBe(source.height);
  expect(inspector.height).toBe(source.height);
  const first = page.getByRole('button', { name: 'Inspect goal ?δ₁ : {toString: String → String}', exact: true });
  const second = page.getByRole('button', { name: 'Inspect goal ?δ₂ : {toString: A → String}', exact: true });
  await expect(second).toHaveText('?δ₂');
  await second.hover();
  await expect(page.getByRole('tooltip', { name: /\?δ₂ : \{toString: A → String\}/ })).toBeVisible();
  await second.click();
  await expect(second).toHaveText('?δ₂ : {toString: A → String}');
  await expect(first).toHaveText('?δ₁');
  await expect(page.locator('.goal-judgment')).toHaveText('Δ ⊢ ?δ₂ : {toString: A → String}');
  const text = await page.locator('.unresolved-code').innerText();
  expect(text).toMatch(/\n\s+toString =\n\s+λ/);
  await page.getByRole('button', { name: 'Fold ToString1', exact: true }).click();
  await expect(first).toHaveCount(0);
  await page.getByRole('combobox', { name: 'Select synthesis goal' }).click();
  await page.getByRole('option', { name: '?δ₁ : {toString: String → String}', exact: true }).click();
  await expect(first).toBeVisible();
  await expect(first).toHaveText('?δ₁ : {toString: String → String}');
  const expanded = page.getByRole('button', { name: /^Inspect goal \?δ₃ :/ });
  await expanded.click();
  const divider = (await page.getByRole('separator').nth(1).boundingBox())!;
  await page.mouse.move(divider.x + divider.width / 2, divider.y + 100);
  await page.mouse.down();
  await page.mouse.move(term.x + 320, divider.y + 100, { steps: 10 });
  await page.mouse.up();
  await expect.poll(() => expanded.innerText()).toContain('\n');
  await expect
    .poll(async () => {
      const marker = (await expanded.boundingBox())!;
      const viewport = (await page.locator('.unresolved-code').boundingBox())!;
      return marker.y >= viewport.y && marker.y + marker.height <= viewport.y + viewport.height;
    })
    .toBe(true);
  expect(await page.locator('.unresolved-code').evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(
    true,
  );
  await page.screenshot({ path: '.cache/screenshots/three-columns.png', fullPage: true, animations: 'disabled' });
});

test('real synthesis supports graph, constraint, model, trace, and export inspection', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('./');
  await expect(page.getByText('Z3 backend ready', { exact: true })).toBeVisible();
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
  await page.getByRole('tab', { name: 'Terms', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Unresolved term' })).toContainText('seed');
  await page.screenshot({ path: '.cache/screenshots/terms.png', fullPage: true });
  expect(errors).toEqual([]);
});

test('editing, sharing, diagnostics, and cancellation use the current source', async ({ page, context }) => {
  await page.goto('./');
  await expect(page.getByText('Z3 backend ready', { exact: true })).toBeVisible();
  await edit(page, program.replace('73', '89'));
  await page.keyboard.press('ControlOrMeta+Enter');
  await expect(page.getByTestId('program-value')).toHaveText('{item = 89}');
  await page.getByRole('button', { name: 'Source actions', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Share program', exact: true }).click();
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
});

test('the workbench remains usable on narrow screens and in dark mode', async ({ page }) => {
  await page.goto('./');
  await expect(page.getByText('Z3 backend ready', { exact: true })).toBeVisible();
  const separator = (await page.getByRole('separator').first().boundingBox())!;
  await page.mouse.move(separator.x + separator.width / 2, separator.y + 100);
  await page.mouse.down();
  await page.mouse.move(400, separator.y + 100, { steps: 10 });
  await page.mouse.up();
  await expect(page.locator('.editor-title').getByRole('button', { name: 'Run program' })).toBeVisible();
  await page.setViewportSize({ width: 420, height: 900 });
  await expect
    .poll(async () => {
      const editor = (await page.locator('.source-panel').boundingBox())!;
      const term = (await page.locator('.term-panel').boundingBox())!;
      const research = (await page.locator('.research-panel').boundingBox())!;
      return term.y >= editor.y + editor.height && research.y >= term.y + term.height;
    })
    .toBe(true);
  await page.getByRole('button', { name: 'Examples', exact: true }).click();
  await page.getByRole('textbox', { name: 'Search examples' }).fill('synthesized totals');
  await page.getByRole('button', { name: /synthesized totals/ }).click();
  const header = page.locator('.editor-title');
  await expect(header.getByRole('button', { name: 'Search settings', exact: true })).toBeVisible();
  await header.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('{bytes = 6144, requests = 150}');
  await page.getByRole('button', { name: 'Toggle color scheme' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-mantine-color-scheme', 'dark');
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(420);
  await page.screenshot({ path: '.cache/screenshots/mobile.png', fullPage: true });
});

test('search bounds and execution deadlines control real computations', async ({ page }) => {
  await page.goto('./');
  await expect(page.getByText('Z3 backend ready', { exact: true })).toBeVisible();
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
  await expect(page.getByText('Z3 backend ready', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => crossOriginIsolated)).toBe(true);
  expect(await page.evaluate(() => navigator.serviceWorker.controller?.scriptURL)).toBe(
    new URL('coi-serviceworker.js', baseURL).href,
  );
  await edit(page, program);
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('{item = 73}');
  await page.reload();
  await expect(page.getByText('Z3 backend ready', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Run program' }).click();
  await expect(page.getByTestId('program-value')).toHaveText('{item = 73}');
});
