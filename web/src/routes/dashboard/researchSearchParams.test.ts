import { describe, it, expect } from 'vitest'
import { readResearchSearch, researchSearchParams } from './researchSearchParams'

/** Read a search straight from a query string, as the tab does on mount. */
function read(query: string) {
  return readResearchSearch(new URLSearchParams(query))
}

/** Write a search onto a query string, returning the result as a plain object. */
function write({
  current = 'tab=research',
  filters = null,
  page = 0,
  sort = null,
  direction = 'ASC' as const,
}: Partial<Omit<Parameters<typeof researchSearchParams>[0], 'current'>> & { current?: string }) {
  return Object.fromEntries(
    researchSearchParams({
      current: new URLSearchParams(current),
      filters,
      page,
      sort,
      direction,
    }),
  )
}

const BLANK_FIELDS = {
  name: '',
  sex: '',
  status: '',
  inCalibration: '',
  ageMin: '',
  ageMax: '',
  ratingMin: '',
  ratingMax: '',
}

describe('readResearchSearch', () => {
  it('reports no search at all for an untouched tab', () => {
    const state = read('tab=research')
    // null, not {} — it is what keeps the results card off screen entirely.
    expect(state.applied).toBeNull()
    expect(state.fields).toEqual(BLANK_FIELDS)
    expect(state.page).toBe(0)
    expect(state.sort).toBeNull()
    expect(state.direction).toBe('ASC')
    expect(state.ignored).toEqual([])
  })

  it('reads every facet as both a filter and the inputs that describe it', () => {
    const state = read('tab=research&name=ali&sex=Male&status=UNCLAIMED&age=[20,30]&rating=[3.0,4.5]')
    expect(state.applied).toEqual({
      name: 'ali',
      sex: 'Male',
      status: 'UNCLAIMED',
      age: '[20,30]',
      rating: '[3.0,4.5]',
    })
    // The form must show exactly the filters that produced the results (#1054).
    expect(state.fields).toEqual({
      name: 'ali',
      sex: 'Male',
      status: 'UNCLAIMED',
      inCalibration: '',
      ageMin: '20',
      ageMax: '30',
      ratingMin: '3.0',
      ratingMax: '4.5',
    })
    expect(state.ignored).toEqual([])
  })

  it('splits a one-sided interval back into the single input that holds it', () => {
    expect(read('age=[20,)').fields).toMatchObject({ ageMin: '20', ageMax: '' })
    expect(read('rating=(,4.5]').fields).toMatchObject({ ratingMin: '', ratingMax: '4.5' })
  })

  it('ignores an interval the two inputs could not reproduce', () => {
    // The API accepts an exclusive bound; two plain number boxes cannot show one, so applying it would
    // put a filter on screen that the next keystroke silently rewrites to [20,30].
    const state = read('name=ali&age=(20,30]')
    expect(state.applied).toEqual({ name: 'ali' })
    expect(state.fields).toMatchObject({ name: 'ali', ageMin: '', ageMax: '' })
    expect(state.ignored).toEqual(['age'])
  })

  it('ignores an interval that is not an interval at all', () => {
    expect(read('rating=3.0-4.0').ignored).toEqual(['rating'])
    expect(read('age=(,)').ignored).toEqual(['age'])
  })

  it('ignores values the API does not define, rather than sending a request it would reject', () => {
    const state = read('sex=Other&status=RETIRED&sort=SHOE_SIZE&direction=SIDEWAYS')
    expect(state.applied).toBeNull()
    expect(state.fields).toEqual(BLANK_FIELDS)
    expect(state.sort).toBeNull()
    expect(state.direction).toBe('ASC')
    expect(state.ignored).toEqual(['sex', 'status', 'sort', 'direction'])
  })

  it('keeps the filters a link got right alongside the one it did not', () => {
    const state = read('name=ali&sex=Female&status=RETIRED')
    expect(state.applied).toEqual({ name: 'ali', sex: 'Female' })
    expect(state.ignored).toEqual(['status'])
  })

  it('reads the human 1-based page as the 0-based index the pager uses', () => {
    expect(read('name=a&page=3').page).toBe(2)
    expect(read('name=a&page=1').page).toBe(0)
  })

  it('ignores a page that is not a page number', () => {
    for (const raw of ['0', '-2', 'abc', '1.5', '']) {
      const state = read(`name=a&page=${raw}`)
      expect(state.page).toBe(0)
      expect(state.ignored).toEqual(raw === '' ? [] : ['page'])
    }
  })

  it('reads sort and direction, defaulting the direction to ascending', () => {
    expect(read('name=a&sort=AGE')).toMatchObject({ sort: 'AGE', direction: 'ASC' })
    expect(read('name=a&sort=RATING&direction=DESC')).toMatchObject({
      sort: 'RATING',
      direction: 'DESC',
    })
  })

  it('trims a name so a padded link and a typed search are one and the same', () => {
    expect(read('name=%20ali%20').applied).toEqual({ name: 'ali' })
  })
})

describe('researchSearchParams', () => {
  it('writes nothing for a default state, so an untouched tab keeps a clean URL', () => {
    expect(write({})).toEqual({ tab: 'research' })
  })

  it('keeps params it does not own, notably the dashboard tab', () => {
    expect(write({ current: 'tab=research&foo=bar', filters: { name: 'ali' } })).toEqual({
      tab: 'research',
      foo: 'bar',
      name: 'ali',
    })
  })

  it('writes the filters, the 1-based page, and a non-default direction', () => {
    expect(
      write({
        filters: { name: 'ali', sex: 'Male', status: 'ACTIVE', age: '[20,30]', rating: '[3.0,)' },
        page: 2,
        sort: 'AGE',
        direction: 'DESC',
      }),
    ).toEqual({
      tab: 'research',
      name: 'ali',
      sex: 'Male',
      status: 'ACTIVE',
      age: '[20,30]',
      rating: '[3.0,)',
      page: '3',
      sort: 'AGE',
      direction: 'DESC',
    })
  })

  it('omits an ascending direction and the first page', () => {
    expect(write({ filters: { name: 'ali' }, page: 0, sort: 'SEX', direction: 'ASC' })).toEqual({
      tab: 'research',
      name: 'ali',
      sort: 'SEX',
    })
  })

  it('omits a direction without a sort, which the API ignores anyway', () => {
    expect(write({ filters: { name: 'ali' }, direction: 'DESC' })).toEqual({
      tab: 'research',
      name: 'ali',
    })
  })

  it('clears what a previous search left behind', () => {
    const current = 'tab=research&name=ali&sex=Male&status=MERGED&age=[20,30]&rating=[3.0,)&page=4&sort=AGE&direction=DESC'
    expect(write({ current })).toEqual({ tab: 'research' })
  })

  it('round-trips a search through the URL unchanged', () => {
    const filters = { name: 'ali', sex: 'Female' as const, age: '[20,30]', rating: '(,4.5]' }
    const url = researchSearchParams({
      current: new URLSearchParams('tab=research'),
      filters,
      page: 2,
      sort: 'LAST_NAME',
      direction: 'DESC',
    })
    const state = readResearchSearch(new URLSearchParams(url.toString()))
    expect(state.applied).toEqual(filters)
    expect(state.page).toBe(2)
    expect(state.sort).toBe('LAST_NAME')
    expect(state.direction).toBe('DESC')
    expect(state.ignored).toEqual([])
  })
})

describe('the calibration facet (#1065)', () => {
  it('keeps `false` through the round trip, where truthiness would drop it', () => {
    // The regression this guards: the FACET_KEYS writer tests truthiness, so `false` — a real filter
    // meaning "settled players only" — would be deleted from the URL rather than written.
    const written = researchSearchParams({
      current: new URLSearchParams('tab=research'),
      filters: { inCalibration: false },
      page: 0,
      sort: null,
      direction: 'ASC',
    })
    expect(written.get('inCalibration')).toBe('false')
    expect(written.get('tab')).toBe('research')

    const read = readResearchSearch(written)
    expect(read.applied).toEqual({ inCalibration: false })
    expect(read.fields.inCalibration).toBe('false')
    expect(read.ignored).toEqual([])
  })

  it('round-trips `true` and omits the param when the filter is absent', () => {
    const on = researchSearchParams({
      current: new URLSearchParams(),
      filters: { inCalibration: true },
      page: 0,
      sort: null,
      direction: 'ASC',
    })
    expect(on.get('inCalibration')).toBe('true')
    expect(readResearchSearch(on).applied).toEqual({ inCalibration: true })

    // Absent means absent — an untouched filter leaves no param behind.
    const off = researchSearchParams({
      current: new URLSearchParams('inCalibration=true'),
      filters: { name: 'ali' },
      page: 0,
      sort: null,
      direction: 'ASC',
    })
    expect(off.has('inCalibration')).toBe(false)
  })

  it('reports a non-boolean as ignored rather than coercing it', () => {
    // Coercing would be worse than dropping: `inCalibration=yes` read as `false` would quietly show
    // settled players to someone who asked for calibrating ones.
    const read = readResearchSearch(new URLSearchParams('name=ali&inCalibration=yes'))
    expect(read.ignored).toContain('inCalibration')
    expect(read.applied).toEqual({ name: 'ali' })
    expect(read.fields.inCalibration).toBe('')
  })

  it('accepts CALIBRATION as a sort key', () => {
    const read = readResearchSearch(new URLSearchParams('name=ali&sort=CALIBRATION&direction=DESC'))
    expect(read.sort).toBe('CALIBRATION')
    expect(read.direction).toBe('DESC')
    expect(read.ignored).toEqual([])
  })
})
