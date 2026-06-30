export function createAsyncIterator(stream) {
  return stream[Symbol.asyncIterator]();
}

export function assignAsyncIterator(iterator) {
  iterator[Symbol.asyncIterator] = function () {
    return this;
  };
  return iterator;
}
