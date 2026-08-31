import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AdminResourceFormPage } from './AdminResourceFormPage'
import type { AdminResourceResponse } from '@/types'

const existingResource: AdminResourceResponse = {
  id: 5,
  name: '스쿼트 랙 A',
  type: 'MACHINE',
  status: 'ACTIVE',
  capacity: 1,
  description: null,
  slotDuration: 30,
  minDuration: 30,
  maxDuration: 90,
  imageUrl: null,
  createdAt: '2026-01-01T00:00:00',
  updatedAt: '2026-01-01T00:00:00',
}

const mockShowToast = vi.fn()
const mockCreateMutateAsync = vi.fn()
const mockUpdateMutateAsync = vi.fn()

vi.mock('@/context/useToast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}))

vi.mock('@/features/admin/AdminResourceImageManager', () => ({
  AdminResourceImageManager: () => <div data-testid="image-manager" />,
}))

vi.mock('@/features/admin/AdminResourceStatusControl', () => ({
  AdminResourceStatusControl: () => <div data-testid="status-control" />,
}))

vi.mock('@/features/admin/hooks', () => ({
  useAdminResource: (resourceId: number) =>
    Number.isFinite(resourceId)
      ? { data: existingResource, isPending: false, isError: false, refetch: vi.fn() }
      : { data: undefined, isPending: false, isError: false, refetch: vi.fn() },
  useCreateAdminResource: () => ({ mutateAsync: mockCreateMutateAsync, isPending: false }),
  useUpdateAdminResource: () => ({ mutateAsync: mockUpdateMutateAsync, isPending: false }),
}))

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/admin/resources" element={<div>RESOURCE_LIST</div>} />
        <Route path="/admin/resources/new" element={<AdminResourceFormPage />} />
        <Route path="/admin/resources/:resourceId/edit" element={<AdminResourceFormPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('AdminResourceFormPage — save UX', () => {
  beforeEach(() => {
    mockShowToast.mockClear()
    mockCreateMutateAsync.mockClear()
    mockUpdateMutateAsync.mockClear()
  })

  it('navigates to the resource list after a successful create', async () => {
    const user = userEvent.setup()
    mockCreateMutateAsync.mockResolvedValue({ id: 9, ...existingResource })
    renderAt('/admin/resources/new')

    await user.type(screen.getByLabelText(/이름/), '레그 프레스 B')
    await user.click(screen.getByRole('button', { name: '등록하기' }))

    expect(await screen.findByText('RESOURCE_LIST')).toBeInTheDocument()
    expect(mockShowToast).toHaveBeenCalledWith(expect.objectContaining({ tone: 'success' }))
  })

  it('navigates to the resource list after a successful update', async () => {
    const user = userEvent.setup()
    mockUpdateMutateAsync.mockResolvedValue(existingResource)
    renderAt('/admin/resources/5/edit')

    await screen.findByDisplayValue('스쿼트 랙 A')
    await user.click(screen.getByRole('button', { name: '저장하기' }))

    expect(await screen.findByText('RESOURCE_LIST')).toBeInTheDocument()
    expect(mockShowToast).toHaveBeenCalledWith(expect.objectContaining({ tone: 'success' }))
  })

  it('stays on the edit screen and shows an error toast when the update API fails', async () => {
    const user = userEvent.setup()
    mockUpdateMutateAsync.mockRejectedValue(new Error('네트워크 오류'))
    renderAt('/admin/resources/5/edit')

    await screen.findByDisplayValue('스쿼트 랙 A')
    await user.click(screen.getByRole('button', { name: '저장하기' }))

    await screen.findByRole('button', { name: '저장하기' })
    expect(screen.queryByText('RESOURCE_LIST')).not.toBeInTheDocument()
    expect(mockShowToast).toHaveBeenCalledWith(expect.objectContaining({ tone: 'danger', title: '저장 실패' }))
  })
})
