document.addEventListener('DOMContentLoaded', () => {
  const cancel = document.querySelector('[data-consent-cancel]')
  cancel?.addEventListener('click', () => {
    const form = cancel.closest('form')
    if (!form) return
    form.querySelectorAll('input[name="scope"]').forEach((input) => {
      input.checked = false
    })
    form.requestSubmit()
  })
})
