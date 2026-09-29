/** Calls the lamp API and returns the parsed JSON; throws Error with a readable message on failure. */
export async function request(path, method = 'GET', body) {
  let response;
  try {
    response = await fetch(path, {
      method,
      headers: { 'X-Requested-With': 'lamp-ui' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new Error('Cannot reach server');
  }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data.error || 'Request failed');
  }
  return data;
}
