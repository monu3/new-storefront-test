/* eslint-disable @typescript-eslint/no-explicit-any */
const hasOwnProperty = Object.prototype.hasOwnProperty

function is(x: any, y: any) {
    if (x === y) {
        return x !== 0 || y !== 0 || 1 / x === 1 / y
    }
    // eslint-disable-next-line no-self-compare
    return x !== x && y !== y
}

export default function shallowEqual(objA: any, objB: any) {
    if (is(objA, objB)) {
        return true
    }

    if (
        typeof objA !== 'object' ||
        objA === null ||
        typeof objB !== 'object' ||
        objB === null
    ) {
        return false
    }

    const keysA = Object.keys(objA)
    const keysB = Object.keys(objB)

    if (keysA.length !== keysB.length) {
        return false
    }

    for (let i = 0; i < keysA.length; i += 1) {
        const key = keysA[i]!
        if (!hasOwnProperty.call(objB, key) || !is(objA[key], objB[key])) {
            return false
        }
    }

    return true
}
