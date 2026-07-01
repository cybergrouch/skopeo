import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NumberedPager } from './NumberedPager'

describe('NumberedPager', () => {
  it('renders nothing when there are no results', () => {
    const { container } = render(<NumberedPager page={0} total={0} pageSize={25} onPage={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the range, total, and numbered page links with the current page marked (#232)', () => {
    render(<NumberedPager page={0} total={60} pageSize={25} onPage={vi.fn()} />)
    expect(screen.getByText('Showing 1–25 of 60')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '1' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('button', { name: '2' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '3' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled()
  })

  it('navigates via a numbered link, Next, and Previous', async () => {
    const onPage = vi.fn()
    const user = userEvent.setup()
    const { rerender } = render(<NumberedPager page={0} total={60} pageSize={25} onPage={onPage} />)

    await user.click(screen.getByRole('button', { name: '3' }))
    expect(onPage).toHaveBeenLastCalledWith(2)
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(onPage).toHaveBeenLastCalledWith(1)

    rerender(<NumberedPager page={2} total={60} pageSize={25} onPage={onPage} />)
    expect(screen.getByText('Showing 51–60 of 60')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Previous' }))
    expect(onPage).toHaveBeenLastCalledWith(1)
  })

  it('windows the page links with ellipses for many pages', () => {
    // 500 items / 25 = 20 pages → windowed, not 20 buttons.
    const { rerender } = render(<NumberedPager page={0} total={500} pageSize={25} onPage={vi.fn()} />)
    // First page: trailing ellipsis only (first, next, …, last).
    expect(screen.getByText('…')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '20' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '10' })).not.toBeInTheDocument()

    // A middle page: ellipses on BOTH sides (first … 10 11 12 … last).
    rerender(<NumberedPager page={10} total={500} pageSize={25} onPage={vi.fn()} />)
    expect(screen.getAllByText('…')).toHaveLength(2)
    expect(screen.getByRole('button', { name: '11' })).toHaveAttribute('aria-current', 'page')

    // The last page: leading ellipsis only, no trailing.
    rerender(<NumberedPager page={19} total={500} pageSize={25} onPage={vi.fn()} />)
    expect(screen.getAllByText('…')).toHaveLength(1)
    expect(screen.getByRole('button', { name: '20' })).toHaveAttribute('aria-current', 'page')
  })
})
