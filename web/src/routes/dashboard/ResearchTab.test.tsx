import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ResearchTab } from './ResearchTab'

const { useGetApiV1Users } = vi.hoisted(() => ({ useGetApiV1Users: vi.fn() }))
vi.mock('@/api/generated/users/users', () => ({ useGetApiV1Users }))

function renderTab() {
  return render(<ResearchTab />)
}

describe('ResearchTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGetApiV1Users.mockReturnValue({ data: undefined, isLoading: false, isError: false })
  })

  it('keeps Search disabled until at least one filter is set', async () => {
    const user = userEvent.setup()
    renderTab()
    const search = screen.getByRole('button', { name: 'Search' })
    expect(search).toBeDisabled()
    await user.type(screen.getByLabelText('Name'), 'al')
    expect(search).toBeEnabled()
  })

  it('searches by name and lists results', async () => {
    useGetApiV1Users.mockReturnValue({
      data: [
        { id: 'u1', displayName: 'Alice', sex: 'Female', dateOfBirth: '2000-01-01', capabilities: ['PLAYER'] },
      ],
      isLoading: false,
      isError: false,
    })
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'ali')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(screen.getByText('Alice')).toBeInTheDocument()
    expect(screen.getByText('2000-01-01')).toBeInTheDocument()
    expect(useGetApiV1Users).toHaveBeenCalledWith({ name: 'ali' }, { query: { enabled: true } })
  })

  it('builds age and rating intervals from min/max inputs', async () => {
    const user = userEvent.setup()
    renderTab()
    await user.selectOptions(screen.getByLabelText('Sex'), 'Male')
    await user.type(screen.getByLabelText('Age from'), '20')
    await user.type(screen.getByLabelText('to', { selector: '#r-age-max' }), '30')
    await user.type(screen.getByLabelText('Rating from'), '3.0')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(useGetApiV1Users).toHaveBeenLastCalledWith(
      { sex: 'Male', age: '[20,30]', rating: '[3.0,)' },
      { query: { enabled: true } },
    )
  })

  it('builds open-lower intervals from max-only inputs', async () => {
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('to', { selector: '#r-age-max' }), '30')
    await user.type(screen.getByLabelText('to', { selector: '#r-rating-max' }), '4.5')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(useGetApiV1Users).toHaveBeenLastCalledWith(
      { age: '(,30]', rating: '(,4.5]' },
      { query: { enabled: true } },
    )
  })

  it('renders a result with missing sex, date, and name using the id', async () => {
    useGetApiV1Users.mockReturnValue({
      data: [{ id: 'abc-123', displayName: null, sex: null, dateOfBirth: null, capabilities: [] }],
      isLoading: false,
      isError: false,
    })
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'x')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('abc-123')).toBeInTheDocument()
  })

  it('shows an empty state when there are no matches', async () => {
    useGetApiV1Users.mockReturnValue({ data: [], isLoading: false, isError: false })
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'zzz')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('No matching players.')).toBeInTheDocument()
  })

  it('shows a loading state while searching', async () => {
    useGetApiV1Users.mockReturnValue({ data: undefined, isLoading: true, isError: false })
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'ali')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText('Searching…')).toBeInTheDocument()
  })

  it('shows an error when the filters are rejected', async () => {
    useGetApiV1Users.mockReturnValue({ data: undefined, isLoading: false, isError: true })
    const user = userEvent.setup()
    renderTab()
    await user.type(screen.getByLabelText('Name'), 'ali')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByText(/Invalid filters/i)).toBeInTheDocument()
  })
})
