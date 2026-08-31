import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router-dom'
import { CheckCircle2 } from 'lucide-react'
import { useAuth } from '@/context/useAuth'
import { Button, FormField, Input } from '@/components/ui'
import { getErrorMessage } from '@/utils/getErrorMessage'
import type { UserSignUpRequest } from '@/types'

export function SignUpPage() {
  const { signUp } = useAuth()
  const navigate = useNavigate()
  const [serverError, setServerError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<UserSignUpRequest>({ mode: 'onBlur' })

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null)
    try {
      await signUp(values)
      setDone(true)
    } catch (error) {
      setServerError(getErrorMessage(error, '회원가입에 실패했습니다.'))
    }
  })

  if (done) {
    return (
      <div className="flex flex-col items-center gap-3 text-center">
        <div className="flex size-12 items-center justify-center rounded-full bg-success-50 text-success-600">
          <CheckCircle2 className="size-6" aria-hidden="true" />
        </div>
        <h1 className="text-lg font-semibold text-neutral-900">회원가입이 완료되었습니다</h1>
        <p className="text-sm text-neutral-500">이제 로그인하여 GymFlow를 이용해 보세요.</p>
        <Button className="mt-2 w-full" onClick={() => navigate('/login', { replace: true })}>
          로그인하러 가기
        </Button>
      </div>
    )
  }

  return (
    <div>
      <h1 className="text-lg font-semibold text-neutral-900">회원가입</h1>
      <p className="mt-1 text-sm text-neutral-500">새 GymFlow 계정을 만드세요.</p>

      <form onSubmit={onSubmit} noValidate className="mt-6 flex flex-col gap-4">
        <FormField label="이름" required error={errors.name?.message}>
          {(id, describedBy) => (
            <Input
              id={id}
              autoComplete="name"
              hasError={!!errors.name}
              aria-describedby={describedBy}
              placeholder="홍길동"
              {...register('name', {
                required: '이름을 입력해 주세요.',
                maxLength: { value: 100, message: '이름은 100자를 초과할 수 없습니다.' },
              })}
            />
          )}
        </FormField>

        <FormField label="이메일" required error={errors.email?.message}>
          {(id, describedBy) => (
            <Input
              id={id}
              type="email"
              autoComplete="email"
              hasError={!!errors.email}
              aria-describedby={describedBy}
              placeholder="you@example.com"
              {...register('email', {
                required: '이메일을 입력해 주세요.',
                pattern: { value: /^[^\s@]+@[^\s@]+\.[^\s@]+$/, message: '올바른 이메일 형식이 아닙니다.' },
                maxLength: { value: 255, message: '이메일은 255자를 초과할 수 없습니다.' },
              })}
            />
          )}
        </FormField>

        <FormField
          label="비밀번호"
          required
          error={errors.password?.message}
          helpText="8자 이상 64자 이하로 입력해 주세요."
        >
          {(id, describedBy) => (
            <Input
              id={id}
              type="password"
              autoComplete="new-password"
              hasError={!!errors.password}
              aria-describedby={describedBy}
              {...register('password', {
                required: '비밀번호를 입력해 주세요.',
                minLength: { value: 8, message: '비밀번호는 8자 이상이어야 합니다.' },
                maxLength: { value: 64, message: '비밀번호는 64자를 초과할 수 없습니다.' },
              })}
            />
          )}
        </FormField>

        {serverError && (
          <p role="alert" className="rounded-md bg-danger-50 px-3 py-2 text-sm text-danger-700">
            {serverError}
          </p>
        )}

        <Button type="submit" className="mt-2 w-full" loading={isSubmitting}>
          회원가입
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-neutral-500">
        이미 계정이 있으신가요?{' '}
        <Link to="/login" className="font-medium text-brand-600 hover:text-brand-700">
          로그인
        </Link>
      </p>
    </div>
  )
}
