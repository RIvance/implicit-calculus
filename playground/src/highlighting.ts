import type { Language } from '../bridge/protocol';

export interface TypeSpan {
  offset: number;
  length: number;
}

/** Recognize type positions without name resolution, including unfinished editor input. */
export function typeIdentifiers(source: string, language: Language): TypeSpan[] {
  const words = new Set(language.keywords.concat(language.literals));
  const identifier = /^[\p{L}_$][\p{L}\p{N}_$]*$/u;
  const tokens = Array.from(
    source.matchAll(
      /\/\/[^\r\n]*|\/\*[\s\S]*?(?:\*\/|$)|"(?:\\.|[^"\\])*(?:"|$)|[\p{L}_$][\p{L}\p{N}_$]*|\d+|::|->|~>|=>|==|[^\s]/gu,
    ),
  ).filter((match) => !match[0].startsWith('//') && !match[0].startsWith('/*'));
  const types = new Set<number>();
  const text = (index: number) => tokens[index]?.[0];
  const isName = (index: number) => identifier.test(text(index) ?? '') && !words.has(text(index));

  function readType(start: number): { end: number; names: number[] } {
    let index = start;
    const names: number[] = [];
    const read = () => {
      const nested = readType(index);
      names.push(...nested.names);
      index = nested.end;
    };
    if (text(index) === 'forall') {
      index++;
      while (isName(index)) {
        names.push(index++);
        if (text(index) !== ',') break;
        index++;
      }
      if (text(index) === '.') {
        index++;
        read();
      }
      return { end: index, names };
    }
    if (isName(index)) {
      names.push(index++);
      if (text(index) === '[') {
        index++;
        read();
        while (text(index) === ',') {
          index++;
          read();
        }
        if (text(index) === ']') index++;
      }
    } else if (text(index) === '(') {
      index++;
      read();
      if (text(index) !== ')') return { end: index, names };
      index++;
    } else if (text(index) === '{') {
      index++;
      while (isName(index) && text(index + 1) === ':') {
        index += 2;
        read();
        if (text(index) === ',' || text(index) === ';') index++;
      }
      if (text(index) !== '}') return { end: index, names };
      index++;
    } else {
      return { end: index, names };
    }
    if (text(index) === '->' || text(index) === '~>') {
      index++;
      read();
    }
    return { end: index, names };
  }

  function markType(start: number) {
    const result = readType(start);
    result.names.forEach((index) => types.add(index));
    return result.end;
  }

  // Brackets contain type parameters, type arguments, or trait constraints.
  function bracket(start: number): number {
    let index = start + 1;
    while (index < tokens.length && text(index) !== ']') {
      const end = markType(index);
      index = end > index ? end : index + 1;
    }
    return text(index) === ']' ? index + 1 : index;
  }

  for (let index = 0; index < tokens.length;) {
    const token = text(index);
    if (token === 'type' || token === 'trait') {
      index++;
      if (isName(index)) types.add(index++);
      if (text(index) === '[') index = bracket(index);
      if (text(index) === '=') index = markType(index + 1);
    } else if (token === 'implement') {
      index++;
      if (text(index) === '[') index = bracket(index);
      index = markType(index);
    } else if (token === '[') {
      index = bracket(index);
    } else if (token === ':') {
      index = markType(index + 1);
    } else {
      index++;
    }
  }
  // Receivers become type positions at ::. Consume each candidate once, even when it is a term.
  for (let index = 0; index < tokens.length;) {
    const receiver = readType(index);
    if (text(receiver.end) === '::') receiver.names.forEach((name) => types.add(name));
    index = Math.max(index + 1, receiver.end);
  }
  return [...types]
    .sort((left, right) => left - right)
    .map((index) => ({
      offset: tokens[index].index,
      length: tokens[index][0].length,
    }));
}
