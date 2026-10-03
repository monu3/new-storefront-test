import { useRef } from 'react'
import uniqueId from 'lodash/uniqueId'
import createUID from '../ui/utils/createUid'

export default function useUniqueId(prefix = '', len = 10) {
    // React 19 types require an initial value.
    const idRef = useRef<string | undefined>(undefined)

    if (!idRef.current) {
        idRef.current = `${uniqueId(prefix)}-${createUID(len)}`
    }

    return idRef.current
}
