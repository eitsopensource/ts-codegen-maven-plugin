export interface Sort {
    orders: SortOrder[]
}

export interface SortOrder {
    direction: SortDirection,
    property: string,
    nullHandlingHint?: NullHandling
}

export type SortDirection = 'ASC' | 'DESC';
export type NullHandling = 'NATIVE' | 'NULLS_FIRST' | 'NULLS_LAST';

export interface PageRequest {
    page: number,
    size: number,
    sort?: Sort
}

export interface Page<T> {
    content: T[],
    totalElements: number,
    numberOfElements: number,
    totalPages: number,
    pageable?: PageRequest
}

@MODELS@