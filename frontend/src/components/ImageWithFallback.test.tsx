import { describe, expect, it } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ImageWithFallback } from './ImageWithFallback'

describe('ImageWithFallback', () => {
  it('renders the fallback placeholder when src is null', () => {
    render(<ImageWithFallback src={null} alt="러닝머신" />)

    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('renders an img element when a src is provided', () => {
    render(<ImageWithFallback src="https://example-bucket.s3.amazonaws.com/img.jpg" alt="러닝머신" />)

    const img = screen.getByRole('img', { name: '러닝머신' })
    expect(img).toHaveAttribute('src', 'https://example-bucket.s3.amazonaws.com/img.jpg')
  })

  it('swaps to the fallback placeholder if the image fails to load (e.g. an expired presigned URL)', () => {
    render(<ImageWithFallback src="https://example-bucket.s3.amazonaws.com/expired.jpg" alt="러닝머신" />)

    const img = screen.getByRole('img', { name: '러닝머신' })
    fireEvent.error(img)

    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('resets the failed state when a new src is provided', () => {
    const { rerender } = render(<ImageWithFallback src="https://example.com/a.jpg" alt="A" />)
    const img = screen.getByRole('img')
    fireEvent.error(img)
    expect(screen.queryByRole('img')).not.toBeInTheDocument()

    rerender(<ImageWithFallback src="https://example.com/b.jpg" alt="B" />)

    expect(screen.getByRole('img', { name: 'B' })).toBeInTheDocument()
  })
})
