document.addEventListener('DOMContentLoaded', () => {
  const form = document.querySelector('form[name="consent_form"]')
  const cancel = document.querySelector('[data-consent-cancel]')
  let submitting = false

  form?.addEventListener('submit', (event) => {
    if (submitting) {
      event.preventDefault()
      return
    }
    submitting = true
    form.setAttribute('aria-busy', 'true')
    form.querySelectorAll('button').forEach((button) => {
      button.setAttribute('aria-disabled', 'true')
    })
    const submit = form.querySelector('[data-consent-submit]')
    if (submit) submit.textContent = 'AUTHORIZING…'
  })

  cancel?.addEventListener('click', () => {
    if (!form || submitting) return
    form.querySelectorAll('input[name="scope"]').forEach((input) => {
      input.checked = false
    })
    form.requestSubmit()
  })
})
