import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Avatar } from './Avatar'

describe('Avatar', () => {
  it('renders the remote image with a meaningful alt when photoUrl is present', () => {
    render(<Avatar photoUrl="https://example.com/ana.jpg" name="Ana" />)
    const img = screen.getByRole('img', { name: "Ana's profile picture" })
    expect(img).toHaveAttribute('src', 'https://example.com/ana.jpg')
  })

  it('renders the initials fallback when photoUrl is null', () => {
    const { container } = render(<Avatar photoUrl={null} name="Ana" />)
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByText('A')).toBeInTheDocument()
  })

  it('uses "P" as the initial for an unnamed player with no photo', () => {
    render(<Avatar photoUrl={null} name={null} />)
    expect(screen.getByText('P')).toBeInTheDocument()
  })

  it('falls back to initials when the remote image fails to load', () => {
    const { container } = render(<Avatar photoUrl="https://example.com/broken.jpg" name="Ben" enlargeable />)
    // The image (and its enlarge trigger) render initially…
    fireEvent.error(screen.getByRole('img', { name: "Ben's profile picture" }))
    // …then, on load error, the avatar collapses to the initials fallback.
    expect(container.querySelector('img')).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.getByText('B')).toBeInTheDocument()
  })

  it('does not make an initials-only avatar interactive even when enlargeable', () => {
    render(<Avatar photoUrl={null} name="Ana" enlargeable />)
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('does not wrap the image in a button when not enlargeable', () => {
    render(<Avatar photoUrl="https://example.com/ana.jpg" name="Ana" />)
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.getByRole('img')).toBeInTheDocument()
  })

  it('opens an enlarged lightbox when an enlargeable image is clicked', async () => {
    const user = userEvent.setup()
    render(<Avatar photoUrl="https://example.com/ana.jpg" name="Ana" enlargeable />)
    await user.click(screen.getByRole('button', { name: "View Ana's profile picture" }))
    const dialog = await screen.findByRole('dialog')
    // The lightbox shows a larger copy of the image with the same meaningful alt.
    const enlarged = screen.getAllByRole('img', { name: "Ana's profile picture" })
    expect(enlarged.length).toBeGreaterThanOrEqual(1)
    expect(dialog).toBeInTheDocument()
  })

  it('closes the lightbox on Escape', async () => {
    const user = userEvent.setup()
    render(<Avatar photoUrl="https://example.com/ana.jpg" name="Ana" enlargeable />)
    await user.click(screen.getByRole('button', { name: "View Ana's profile picture" }))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull()
    })
  })

  it('closes the lightbox on a ✕ button click', async () => {
    const user = userEvent.setup()
    render(<Avatar photoUrl="https://example.com/ana.jpg" name="Ana" enlargeable />)
    await user.click(screen.getByRole('button', { name: "View Ana's profile picture" }))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Close' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull()
    })
  })

  it('closes the lightbox on a backdrop click', async () => {
    const user = userEvent.setup()
    render(<Avatar photoUrl="https://example.com/ana.jpg" name="Ana" enlargeable />)
    await user.click(screen.getByRole('button', { name: "View Ana's profile picture" }))
    const dialog = await screen.findByRole('dialog')
    // Radix dismisses on a pointer-down outside the content; the overlay behind it is that region.
    fireEvent.pointerDown(document.body)
    fireEvent.click(document.body)
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull()
    })
    expect(dialog).not.toBeInTheDocument()
  })
})
