import { expect, test } from 'vitest';
import { language } from '@implicit/compiler';
import { typeIdentifiers } from './highlighting';

function highlighted(source: string) {
  return typeIdentifiers(source, language()).map(({ offset, length }) => source.slice(offset, offset + length));
}

test('primitive types are types rather than keywords', () => {
  expect(language().primitiveTypes).toEqual(['Bool', 'Int', 'String']);
  for (const name of language().primitiveTypes) expect(language().keywords).not.toContain(name);
  expect(highlighted('let value: Int = 1 in (value: Int)')).toEqual(['Int', 'Int']);
});

test('type positions distinguish identically spelled term and type names without capitalization rules', () => {
  expect(
    highlighted(`type item = Int
def Item[item](Item: item): item = Item
Item[item](Item)`),
  ).toEqual(['item', 'Int', 'item', 'item', 'item', 'item']);
});

test('type parameters, trait constraints and implementation targets are types', () => {
  expect(highlighted('trait ToString[A] = {\n  toString: A -> String\n}')).toEqual(['ToString', 'A', 'A', 'String']);
  expect(highlighted('def display[A where ToString[A]](x: A): String')).toEqual(['A', 'ToString', 'A', 'A', 'String']);
  expect(
    highlighted(`trait Show[a] = { show: a -> String }
implement[a where Show[a] and Eq[a]] Show[a] = { show = x => x }
instance value[a](x: a, implicit count: Int): Box[a] = x`),
  ).toEqual(['Show', 'a', 'a', 'String', 'a', 'Show', 'a', 'Eq', 'a', 'Show', 'a', 'a', 'a', 'Int', 'Box', 'a']);
});

test('nested record fields and term binders retain term highlighting', () => {
  expect(
    highlighted(`let function: forall a, b. { first: a; nested: { second: b } } ~> (a -> b) =
(implicit record: { first: Int, nested: { second: String } }, x: Int) => record.nested.second
function[Int, String](using { first = 1, nested = { second = "type String: Int" } }, 2)`),
  ).toEqual(['a', 'b', 'a', 'b', 'a', 'b', 'Int', 'String', 'Int', 'Int', 'String']);
});

test('qualified selection recognizes nested type receivers, keeping selected members ordinary', () => {
  expect(
    highlighted(`Show[Box[Int]]::show(value)
{ show: (forall a. a -> a) }::show(value)
(Int -> String)::apply(value)
record.show`),
  ).toEqual(['Show', 'Box', 'Int', 'a', 'a', 'a', 'Int', 'String']);
});

test('comments, multiline types, unfinished annotations, and adjacent expressions keep their boundaries', () => {
  expect(
    highlighted(`type box[a] = {
  field: /* String */ a
  next: Int
}
let value:
  box[/* comment */ String] = input
value
// type Fake = String
let unfinished: box[`),
  ).toEqual(['box', 'a', 'a', 'Int', 'box', 'String', 'box']);
});
