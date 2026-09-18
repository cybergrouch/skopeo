import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation, useNavigationType } from 'react-router-dom'
import {
  choiceField,
  pageField,
  readUrlView,
  textField,
  urlViewParamName,
  urlViewParams,
  useUrlViewState,
} from './useUrlViewState'

/** A stand-in for NTRP_LEVELS: a small fixed set, so "not one of these" is easy to write. */
const BANDS = ['3.0', '3.5', '4.0'] as const

/** One of each field kind, which is also the shape `PlayerMatchesPage` adopts. */
const FIELDS = { page: pageField(), band: choiceField(BANDS), search: textField() }

interface View {
  page: number
  band: string
  search: string
}

const DEFAULTS: View = { page: 0, band: '', search: '' }

function read(query: string, ns?: string) {
  return readUrlView({ params: new URLSearchParams(query), fields: FIELDS, ns })
}

function write(values: View, { current = '', ns }: { current?: string; ns?: string } = {}) {
  return Object.fromEntries(
    urlViewParams({ current: new URLSearchParams(current), values, fields: FIELDS, ns }),
  )
}

describe('urlViewParamName', () => {
  it('uses the bare key when a page owns its view state alone', () => {
    expect(urlViewParamName('page')).toBe('page')
  })

  it('prefixes the namespace so repeated components on one page cannot collide', () => {
    expect(urlViewParamName('page', 'upcoming')).toBe('upcoming.page')
  })
})

describe('readUrlView', () => {
  it('falls back to every default for a pristine URL', () => {
    const { values, ignored } = read('')
    expect(values).toEqual(DEFAULTS)
    expect(ignored).toEqual([])
  })

  it('reads each field, with the human 1-based page as the 0-based index the pager uses', () => {
    const { values, ignored } = read('page=3&band=3.5&search=ben')
    expect(values).toEqual({ page: 2, band: '3.5', search: 'ben' })
    expect(ignored).toEqual([])
  })

  it('reads namespaced params and ignores the bare ones a sibling instance owns', () => {
    const { values } = read('page=3&upcoming.page=5', 'upcoming')
    expect(values.page).toBe(4)
  })

  it('treats an empty param as absent rather than unusable', () => {
    const { values, ignored } = read('page=&band=&search=')
    expect(values).toEqual(DEFAULTS)
    expect(ignored).toEqual([])
  })

  it('reports an unusable value under the name it has in the URL, and shows the default', () => {
    const { values, ignored } = read('upcoming.page=nope', 'upcoming')
    expect(values.page).toBe(0)
    expect(ignored).toEqual(['upcoming.page'])
  })

  it('keeps the fields a link got right alongside the one it did not', () => {
    const { values, ignored } = read('page=2&band=9.9&search=ben')
    expect(values).toEqual({ page: 1, band: '', search: 'ben' })
    expect(ignored).toEqual(['band'])
  })
})

describe('urlViewParams', () => {
  it('writes nothing for a default view, so an untouched page has a clean URL', () => {
    expect(write(DEFAULTS)).toEqual({})
  })

  it('writes each non-default value, the page 1-based', () => {
    expect(write({ page: 2, band: '4.0', search: 'ben' })).toEqual({
      page: '3',
      band: '4.0',
      search: 'ben',
    })
  })

  it('namespaces everything it writes', () => {
    expect(write({ ...DEFAULTS, page: 1 }, { ns: 'finalized' })).toEqual({
      'finalized.page': '2',
    })
  })

  it('clears what a previous view left behind rather than leaving it in the URL', () => {
    expect(write(DEFAULTS, { current: 'page=4&band=3.0&search=ben' })).toEqual({})
  })

  it('keeps params it does not own — the dashboard tab and a sibling instance', () => {
    expect(
      write({ ...DEFAULTS, page: 1 }, { current: 'tab=research&finalized.page=9', ns: 'upcoming' }),
    ).toEqual({ tab: 'research', 'finalized.page': '9', 'upcoming.page': '2' })
  })

  it('round-trips a view through the URL unchanged', () => {
    const values: View = { page: 5, band: '3.0', search: 'cara' }
    const params = urlViewParams({
      current: new URLSearchParams('tab=x'),
      values,
      fields: FIELDS,
      ns: 'matches',
    })
    const back = readUrlView({ params, fields: FIELDS, ns: 'matches' })
    expect(back.values).toEqual(values)
    expect(back.ignored).toEqual([])
  })
})

describe('pageField', () => {
  const field = pageField()

  it('rejects anything that is not a page a pager could show', () => {
    for (const raw of ['0', '-2', '1.5', 'abc', '']) {
      expect(field.parse(raw)).toBeNull()
    }
  })

  it('reads the nth page as index n-1', () => {
    expect(field.parse('1')).toBe(0)
    expect(field.parse('7')).toBe(6)
  })

  it('omits the first page and writes every other one 1-based', () => {
    expect(field.format(0)).toBeNull()
    expect(field.format(3)).toBe('4')
  })
})

describe('choiceField', () => {
  const field = choiceField(BANDS)

  it('accepts a value from the set and rejects one the API would refuse', () => {
    expect(field.parse('3.5')).toBe('3.5')
    expect(field.parse('9.9')).toBeNull()
  })

  it('omits the "no filter" fallback and writes any real choice', () => {
    expect(field.format('')).toBeNull()
    expect(field.format('4.0')).toBe('4.0')
  })

  it('honours a non-blank fallback as the value that means "unset"', () => {
    const withDefault = choiceField(BANDS, '3.0')
    expect(withDefault.fallback).toBe('3.0')
    expect(withDefault.format('3.0')).toBeNull()
    expect(withDefault.format('3.5')).toBe('3.5')
  })
})

describe('textField', () => {
  const field = textField()

  it('trims, so a padded link and a typed search are the same view', () => {
    expect(field.parse('  ben  ')).toBe('ben')
    expect(field.format('  ben  ')).toBe('ben')
  })

  it('omits blank text, including text that is only whitespace', () => {
    expect(field.format('')).toBeNull()
    expect(field.format('   ')).toBeNull()
  })

  it('never reports free text as unusable — any string is a searchable string', () => {
    expect(field.parse('%%%')).toBe('%%%')
  })
})

/** Shows what one instance of the hook reads, plus how the URL was changed and by which navigation. */
function Probe({ ns, label }: { ns?: string; label: string }) {
  const { view, setView, ignored } = useUrlViewState<View>({ ns, fields: FIELDS })
  return (
    <div>
      <div data-testid={`${label}-view`}>{`${view.page}|${view.band}|${view.search}`}</div>
      <div data-testid={`${label}-ignored`}>{ignored.join(', ')}</div>
      <button onClick={() => setView({ page: view.page + 1 })}>next {label}</button>
      <button onClick={() => setView({ band: '4.0', page: 0 })}>filter {label}</button>
    </div>
  )
}

function UrlProbe() {
  const location = useLocation()
  const navigationType = useNavigationType()
  return (
    <>
      <div data-testid="url">{decodeURIComponent(location.search)}</div>
      <div data-testid="nav-type">{navigationType}</div>
    </>
  )
}

function renderProbes(entry: string) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      {/* Two namespaced instances plus one un-namespaced, the three shapes in use today. */}
      <Probe ns="upcoming" label="upcoming" />
      <Probe ns="finalized" label="finalized" />
      <Probe label="bare" />
      <UrlProbe />
    </MemoryRouter>,
  )
}

function url() {
  return screen.getByTestId('url').textContent
}

describe('useUrlViewState', () => {
  it('reads each instance from its own namespace', () => {
    renderProbes('/clubs/CLB001?upcoming.page=2&finalized.page=4&page=3&band=3.5')
    expect(screen.getByTestId('upcoming-view')).toHaveTextContent('1||')
    expect(screen.getByTestId('finalized-view')).toHaveTextContent('3||')
    expect(screen.getByTestId('bare-view')).toHaveTextContent('2|3.5|')
  })

  it('pages one instance without touching its siblings', async () => {
    const user = userEvent.setup()
    renderProbes('/clubs/CLB001?finalized.page=4')
    await user.click(screen.getByRole('button', { name: 'next upcoming' }))

    // Exactly the hardest requirement: three cards on one page, paged independently.
    expect(url()).toBe('?finalized.page=4&upcoming.page=2')
    expect(screen.getByTestId('upcoming-view')).toHaveTextContent('1||')
    expect(screen.getByTestId('finalized-view')).toHaveTextContent('3||')
  })

  it('writes with replace, so a page change is not its own Back step (#323)', async () => {
    const user = userEvent.setup()
    renderProbes('/clubs/CLB001')
    await user.click(screen.getByRole('button', { name: 'next bare' }))
    expect(url()).toBe('?page=2')
    expect(screen.getByTestId('nav-type')).toHaveTextContent('REPLACE')
  })

  it('applies a whole patch in one write, so a filter can also reset the page', async () => {
    const user = userEvent.setup()
    renderProbes('/clubs/CLB001?page=4')
    await user.click(screen.getByRole('button', { name: 'filter bare' }))
    // Both changes land: the band is set and the page is back to its (omitted) default.
    expect(url()).toBe('?band=4.0')
    expect(screen.getByTestId('bare-view')).toHaveTextContent('0|4.0|')
  })

  it('surfaces an unusable param, namespaced, while still rendering the view', () => {
    renderProbes('/clubs/CLB001?upcoming.page=nope&band=9.9')
    expect(screen.getByTestId('upcoming-ignored')).toHaveTextContent('upcoming.page')
    expect(screen.getByTestId('upcoming-view')).toHaveTextContent('0||')
    expect(screen.getByTestId('bare-ignored')).toHaveTextContent('band')
    // The bad params are left in the URL until something is written; nothing has thrown.
    expect(url()).toBe('?upcoming.page=nope&band=9.9')
  })

  it('drops an unusable param the first time that instance writes', async () => {
    const user = userEvent.setup()
    renderProbes('/clubs/CLB001?page=nope')
    await user.click(screen.getByRole('button', { name: 'next bare' }))
    expect(url()).toBe('?page=2')
    expect(screen.getByTestId('bare-ignored').textContent).toBe('')
  })
})
