import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { Button, Card, ErrorState, FormField, Input, PageHeader, PageSpinner, Select, Textarea } from '@/components/ui'
import { AdminResourceImageManager } from '@/features/admin/AdminResourceImageManager'
import { AdminResourceStatusControl } from '@/features/admin/AdminResourceStatusControl'
import { useAdminResource, useCreateAdminResource, useUpdateAdminResource } from '@/features/admin/hooks'
import { useToast } from '@/context/useToast'
import { getErrorMessage } from '@/utils/getErrorMessage'
import { resourceTypeLabels } from '@/utils/labels'
import type { ResourceType } from '@/types'

const RESOURCE_TYPE_OPTIONS: ResourceType[] = ['MACHINE', 'PT_ROOM', 'LOCKER', 'STRETCH_ZONE', 'SAUNA', 'SHOWER_ROOM']

interface FormValues {
  name: string
  type: ResourceType
  capacity: number
  description: string
  slotDuration: number
  minDuration: number
  maxDuration: number
}

const DEFAULT_VALUES: FormValues = {
  name: '',
  type: 'MACHINE',
  capacity: 1,
  description: '',
  slotDuration: 30,
  minDuration: 30,
  maxDuration: 120,
}

export function AdminResourceFormPage() {
  const params = useParams<{ resourceId: string }>()
  const isEditMode = params.resourceId !== undefined
  const resourceId = Number(params.resourceId)
  const navigate = useNavigate()
  const { showToast } = useToast()

  const adminResourceQuery = useAdminResource(isEditMode ? resourceId : Number.NaN)
  const createResource = useCreateAdminResource()
  const updateResource = useUpdateAdminResource()

  const {
    register,
    handleSubmit,
    reset,
    watch,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ defaultValues: DEFAULT_VALUES })

  useEffect(() => {
    if (adminResourceQuery.data) {
      reset({
        name: adminResourceQuery.data.name,
        type: adminResourceQuery.data.type,
        capacity: adminResourceQuery.data.capacity,
        description: adminResourceQuery.data.description ?? '',
        slotDuration: adminResourceQuery.data.slotDuration,
        minDuration: adminResourceQuery.data.minDuration,
        maxDuration: adminResourceQuery.data.maxDuration,
      })
    }
  }, [adminResourceQuery.data, reset])

  if (isEditMode && adminResourceQuery.isPending) {
    return <PageSpinner label="Resource 정보를 불러오는 중입니다..." />
  }

  if (isEditMode && adminResourceQuery.isError) {
    return <ErrorState error={adminResourceQuery.error} onRetry={() => adminResourceQuery.refetch()} />
  }

  const onSubmit = handleSubmit(async (values) => {
    // Mirrors AdminResourceService.validatePolicyCombination: max >= min, and
    // both must be exact multiples of the slot duration. Server-side remains
    // the source of truth; this only avoids a round-trip for an easy mistake.
    if (values.maxDuration < values.minDuration) {
      setError('maxDuration', { message: '최대 이용 시간은 최소 이용 시간보다 크거나 같아야 합니다.' })
      return
    }
    if (values.minDuration % values.slotDuration !== 0 || values.maxDuration % values.slotDuration !== 0) {
      setError('slotDuration', { message: '최소/최대 이용 시간은 예약 단위의 배수여야 합니다.' })
      return
    }

    try {
      if (isEditMode) {
        const updated = await updateResource.mutateAsync({
          resourceId,
          payload: {
            name: values.name,
            capacity: values.capacity,
            description: values.description || undefined,
            slotDuration: values.slotDuration,
            minDuration: values.minDuration,
            maxDuration: values.maxDuration,
          },
        })
        showToast({ tone: 'success', title: 'Resource가 수정되었습니다' })
        reset({
          name: updated.name,
          type: updated.type,
          capacity: updated.capacity,
          description: updated.description ?? '',
          slotDuration: updated.slotDuration,
          minDuration: updated.minDuration,
          maxDuration: updated.maxDuration,
        })
      } else {
        const created = await createResource.mutateAsync({
          name: values.name,
          type: values.type,
          capacity: values.capacity,
          description: values.description || undefined,
          slotDuration: values.slotDuration,
          minDuration: values.minDuration,
          maxDuration: values.maxDuration,
        })
        showToast({ tone: 'success', title: 'Resource가 등록되었습니다' })
        navigate(`/admin/resources/${created.id}/edit`, { replace: true })
      }
    } catch (error) {
      showToast({ tone: 'danger', title: '저장 실패', description: getErrorMessage(error) })
    }
  })

  return (
    <div className="mx-auto max-w-2xl">
      <Link
        to="/admin/resources"
        className="mb-4 inline-flex items-center gap-1.5 text-sm text-neutral-500 hover:text-neutral-700"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        Resource 목록
      </Link>

      <PageHeader
        title={isEditMode ? 'Resource 수정' : '새 Resource 등록'}
        description={isEditMode ? '기존 Resource 정보를 수정합니다.' : '새로운 시설/장비를 등록합니다.'}
      />

      {isEditMode && adminResourceQuery.data && (
        <Card className="mb-6 p-6">
          <h2 className="mb-3 text-sm font-semibold text-neutral-700">상태 관리</h2>
          <AdminResourceStatusControl resourceId={resourceId} currentStatus={adminResourceQuery.data.status} />

          <h2 className="mb-3 mt-6 text-sm font-semibold text-neutral-700">이미지</h2>
          <AdminResourceImageManager
            resourceId={resourceId}
            imageUrl={adminResourceQuery.data.imageUrl}
            resourceName={adminResourceQuery.data.name}
          />
        </Card>
      )}

      <Card className="p-6">
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
          <FormField label="이름" required error={errors.name?.message}>
            {(id) => (
              <Input
                id={id}
                hasError={!!errors.name}
                {...register('name', {
                  required: '이름을 입력해 주세요.',
                  maxLength: { value: 100, message: '이름은 100자를 초과할 수 없습니다.' },
                })}
              />
            )}
          </FormField>

          <FormField
            label="유형"
            required
            helpText={isEditMode ? '유형은 등록 이후 변경할 수 없습니다.' : undefined}
          >
            {(id) =>
              isEditMode ? (
                <Input id={id} disabled value={resourceTypeLabels[watch('type')]} />
              ) : (
                <Select id={id} {...register('type', { required: true })}>
                  {RESOURCE_TYPE_OPTIONS.map((type) => (
                    <option key={type} value={type}>
                      {resourceTypeLabels[type]}
                    </option>
                  ))}
                </Select>
              )
            }
          </FormField>

          <FormField label="정원" required error={errors.capacity?.message}>
            {(id) => (
              <Input
                id={id}
                type="number"
                min={1}
                hasError={!!errors.capacity}
                {...register('capacity', {
                  required: '정원을 입력해 주세요.',
                  valueAsNumber: true,
                  min: { value: 1, message: '정원은 1명 이상이어야 합니다.' },
                })}
              />
            )}
          </FormField>

          <FormField label="설명" error={errors.description?.message} helpText="최대 500자">
            {(id) => (
              <Textarea
                id={id}
                rows={3}
                hasError={!!errors.description}
                {...register('description', {
                  maxLength: { value: 500, message: '설명은 500자를 초과할 수 없습니다.' },
                })}
              />
            )}
          </FormField>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <FormField label="예약 단위(분)" required error={errors.slotDuration?.message}>
              {(id) => (
                <Input
                  id={id}
                  type="number"
                  min={1}
                  hasError={!!errors.slotDuration}
                  {...register('slotDuration', {
                    required: true,
                    valueAsNumber: true,
                    min: { value: 1, message: '1분 이상이어야 합니다.' },
                  })}
                />
              )}
            </FormField>
            <FormField label="최소 이용(분)" required error={errors.minDuration?.message}>
              {(id) => (
                <Input
                  id={id}
                  type="number"
                  min={1}
                  hasError={!!errors.minDuration}
                  {...register('minDuration', {
                    required: true,
                    valueAsNumber: true,
                    min: { value: 1, message: '1분 이상이어야 합니다.' },
                  })}
                />
              )}
            </FormField>
            <FormField label="최대 이용(분)" required error={errors.maxDuration?.message}>
              {(id) => (
                <Input
                  id={id}
                  type="number"
                  min={1}
                  hasError={!!errors.maxDuration}
                  {...register('maxDuration', {
                    required: true,
                    valueAsNumber: true,
                    min: { value: 1, message: '1분 이상이어야 합니다.' },
                  })}
                />
              )}
            </FormField>
          </div>

          <Button type="submit" size="lg" loading={isSubmitting} className="mt-2">
            {isEditMode ? '저장하기' : '등록하기'}
          </Button>
        </form>
      </Card>
    </div>
  )
}
