import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminResourcesApi } from '@/api'
import { queryKeys } from '@/api/queryKeys'
import type { AdminResourceStatisticsParams } from '@/api/adminResources'
import type {
  AdminResourceCreateRequest,
  AdminResourceStatusUpdateRequest,
  AdminResourceUpdateRequest,
} from '@/types'

export function useAdminResource(resourceId: number) {
  return useQuery({
    queryKey: queryKeys.adminResources.detail(resourceId),
    queryFn: () => adminResourcesApi.getResource(resourceId),
    enabled: Number.isFinite(resourceId),
  })
}

export function useAdminResourceStatistics(resourceId: number, params?: AdminResourceStatisticsParams) {
  return useQuery({
    queryKey: queryKeys.adminResources.statistics(resourceId, params?.from, params?.to),
    queryFn: () => adminResourcesApi.getStatistics(resourceId, params),
    enabled: Number.isFinite(resourceId),
  })
}

function useInvalidateResourceCaches() {
  const queryClient = useQueryClient()
  return (resourceId: number) => {
    queryClient.invalidateQueries({ queryKey: queryKeys.adminResources.detail(resourceId) })
    queryClient.invalidateQueries({ queryKey: queryKeys.resources.detail(resourceId) })
    queryClient.invalidateQueries({ queryKey: queryKeys.resources.all() })
  }
}

export function useCreateAdminResource() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload: AdminResourceCreateRequest) => adminResourcesApi.create(payload),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.resources.all() }),
  })
}

export function useUpdateAdminResource() {
  const invalidate = useInvalidateResourceCaches()
  return useMutation({
    mutationFn: ({ resourceId, payload }: { resourceId: number; payload: AdminResourceUpdateRequest }) =>
      adminResourcesApi.update(resourceId, payload),
    onSuccess: (_data, variables) => invalidate(variables.resourceId),
  })
}

export function useChangeResourceStatus() {
  const invalidate = useInvalidateResourceCaches()
  return useMutation({
    mutationFn: ({ resourceId, payload }: { resourceId: number; payload: AdminResourceStatusUpdateRequest }) =>
      adminResourcesApi.changeStatus(resourceId, payload),
    onSuccess: (_data, variables) => invalidate(variables.resourceId),
  })
}

export function useUploadResourceImage() {
  const invalidate = useInvalidateResourceCaches()
  return useMutation({
    mutationFn: ({ resourceId, file }: { resourceId: number; file: File }) =>
      adminResourcesApi.uploadImage(resourceId, file),
    onSuccess: (_data, variables) => invalidate(variables.resourceId),
  })
}

export function useDeleteResourceImage() {
  const invalidate = useInvalidateResourceCaches()
  return useMutation({
    mutationFn: (resourceId: number) => adminResourcesApi.deleteImage(resourceId),
    onSuccess: (_data, resourceId) => invalidate(resourceId),
  })
}
