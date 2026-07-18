import { describe, it, expect } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfidenceValue, CONFIDENCE_EXPLANATION } from './ConfidenceValue'

describe('ConfidenceValue', () => {
  it('renders the formatted percent with an interactive trigger', () => {
    render(<ConfidenceValue confidence="0.4" />)
    const trigger = screen.getByRole('button', { name: /rating confidence 40%/i })
    expect(trigger).toBeInTheDocument()
    expect(trigger).toHaveTextContent('40%')
  })

  it('renders nothing when there is no value', () => {
    const { container: nullContainer } = render(
      <ConfidenceValue confidence={null} />,
    )
    expect(nullContainer).toBeEmptyDOMElement()

    const { container: emptyContainer } = render(
      <ConfidenceValue confidence="" />,
    )
    expect(emptyContainer).toBeEmptyDOMElement()

    const { container: undefinedContainer } = render(<ConfidenceValue />)
    expect(undefinedContainer).toBeEmptyDOMElement()
  })

  it('surfaces the explanation on click', async () => {
    const user = userEvent.setup()
    render(<ConfidenceValue confidence="0.75" />)
    expect(screen.queryByText(CONFIDENCE_EXPLANATION)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /rating confidence/i }))

    await waitFor(() => {
      expect(screen.getByText(CONFIDENCE_EXPLANATION)).toBeInTheDocument()
    })
  })

  it('associates the trigger with the explanation for screen readers', () => {
    render(<ConfidenceValue confidence="0.4" />)
    const trigger = screen.getByRole('button', { name: /rating confidence/i })
    expect(trigger).toHaveAttribute('aria-describedby')
  })
})
