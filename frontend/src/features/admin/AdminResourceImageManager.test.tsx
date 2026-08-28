import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
import { AdminResourceImageManager } from './AdminResourceImageManager'

const mockShowToast = vi.fn()
const mockUploadMutate = vi.fn()
const mockDeleteMutate = vi.fn()

vi.mock('@/context/useToast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}))

vi.mock('./hooks', () => ({
  useUploadResourceImage: () => ({ mutate: mockUploadMutate, isPending: false }),
  useDeleteResourceImage: () => ({ mutate: mockDeleteMutate, isPending: false }),
}))

function selectFile(file: File) {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement
  fireEvent.change(input, { target: { files: [file] } })
}

describe('AdminResourceImageManager client-side validation', () => {
  beforeEach(() => {
    mockShowToast.mockClear()
    mockUploadMutate.mockClear()
  })

  it('rejects an unsupported file type without calling the upload mutation', () => {
    render(<AdminResourceImageManager resourceId={1} imageUrl={null} resourceName="런닝머신 A" />)

    const file = new File(['plain text'], 'notes.txt', { type: 'text/plain' })
    selectFile(file)

    expect(mockUploadMutate).not.toHaveBeenCalled()
    expect(mockShowToast).toHaveBeenCalledWith(
      expect.objectContaining({ tone: 'danger', title: '지원하지 않는 파일 형식' }),
    )
  })

  it('rejects a file larger than 5MB without calling the upload mutation', () => {
    render(<AdminResourceImageManager resourceId={1} imageUrl={null} resourceName="런닝머신 A" />)

    const oversized = new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'huge.jpg', { type: 'image/jpeg' })
    selectFile(oversized)

    expect(mockUploadMutate).not.toHaveBeenCalled()
    expect(mockShowToast).toHaveBeenCalledWith(
      expect.objectContaining({ tone: 'danger', title: '파일 용량 초과' }),
    )
  })

  it('accepts a valid JPEG under the size limit and calls the upload mutation', () => {
    render(<AdminResourceImageManager resourceId={1} imageUrl={null} resourceName="런닝머신 A" />)

    const validFile = new File([new Uint8Array(1024)], 'photo.jpg', { type: 'image/jpeg' })
    selectFile(validFile)

    expect(mockUploadMutate).toHaveBeenCalledTimes(1)
    expect(mockUploadMutate).toHaveBeenCalledWith(
      { resourceId: 1, file: validFile },
      expect.anything(),
    )
  })
})
